#include <jni.h>
#include "log.h"
#include "utils.h"
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include <libavformat/avformat.h>
}

namespace {
struct DescriptorInput {
    int fd;
    int64_t position = 0;
    bool seekable;
};

int descriptor_read(void *opaque, uint8_t *buffer, int size) {
    auto *input = static_cast<DescriptorInput *>(opaque);
    ssize_t count;
    do {
        count = input->seekable ? pread64(input->fd, buffer, size, input->position)
                                : read(input->fd, buffer, size);
    } while (count < 0 && errno == EINTR);
    if (count < 0) return AVERROR(errno);
    input->position += count;
    return count ? static_cast<int>(count) : AVERROR_EOF;
}

int64_t descriptor_seek(void *opaque, int64_t offset, int whence) {
    auto *input = static_cast<DescriptorInput *>(opaque);
    struct stat64 status;
    if (fstat64(input->fd, &status) < 0) return AVERROR(errno);
    if (!input->seekable || !S_ISREG(status.st_mode)) return AVERROR(ENOSYS);
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return status.st_size;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? input->position
                   : whence == SEEK_END ? status.st_size : -1;
    int64_t position;
    if (base < 0 || __builtin_add_overflow(base, offset, &position) || position < 0) return AVERROR(EINVAL);
    return input->position = position;
}

void descriptor_close(AVIOContext *io) {
    if (!io) return;
    auto *input = static_cast<DescriptorInput *>(io->opaque);
    close(input->fd);
    delete input;
    av_freep(&io->buffer);
    avio_context_free(&io);
}
}

int media_input_open(AVFormatContext **context, const char *source, int descriptor) {
    if (descriptor < 0) return avformat_open_input(context, source, nullptr, nullptr);
    int fd = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return AVERROR(errno);
    auto *input = new DescriptorInput{fd, 0, lseek64(fd, 0, SEEK_CUR) >= 0};
    auto *buffer = static_cast<uint8_t *>(av_malloc(64 * 1024));
    AVIOContext *io = buffer ? avio_alloc_context(buffer, 64 * 1024, 0, input,
                                                 descriptor_read, nullptr, descriptor_seek) : nullptr;
    if (!io) { av_free(buffer); close(fd); delete input; return AVERROR(ENOMEM); }
    io->seekable = input->seekable ? AVIO_SEEKABLE_NORMAL : 0;
    *context = avformat_alloc_context();
    if (!*context) { descriptor_close(io); return AVERROR(ENOMEM); }
    (*context)->pb = io;
    (*context)->flags |= AVFMT_FLAG_CUSTOM_IO;
    int result = avformat_open_input(context, nullptr, nullptr, nullptr);
    if (result < 0) descriptor_close(io);
    return result;
}

void media_input_close(AVFormatContext **context) {
    AVIOContext *io = *context && ((*context)->flags & AVFMT_FLAG_CUSTOM_IO) ? (*context)->pb : nullptr;
    avformat_close_input(context);
    descriptor_close(io);
}

struct fields fields;
static JavaVM *javaVM;


JNIEnv *utils_get_env() {
    JNIEnv *env;
    if (javaVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

int utils_fields_init(JavaVM *vm) {
    javaVM = vm;

    JNIEnv *env = utils_get_env();
    if (env == nullptr) {
        return -1;
    }

#define GET_CLASS(clazz, str, b_global) do { \
        (clazz) = env->FindClass((str)); \
        if (!(clazz)) { \
            LOGE("FindClass(%s) failed", (str)); \
            return -1; \
        } \
        if (b_global) { \
            (clazz) = (jclass) env->NewGlobalRef((clazz)); \
            if (!(clazz)) { \
                LOGE("NewGlobalRef(%s) failed", (str)); \
                return -1; \
            } \
        } \
    } while (0)

#define GET_ID(get, id, clazz, str, args) do { \
        (id) = env->get((clazz), (str), (args)); \
        if (!(id)) { \
            LOGE(#get"(%s) failed", (str)); \
            return -1; \
        } \
    } while (0)

    GET_CLASS(fields.MediaInfoBuilder.clazz,
              "io/github/anilbeesetti/nextlib/mediainfo/MediaInfoBuilder", true);

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onErrorID,
           fields.MediaInfoBuilder.clazz,
           "onError", "()V");

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onMediaInfoFoundID,
           fields.MediaInfoBuilder.clazz,
           "onMediaInfoFound", "(Ljava/lang/String;J)V");

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onVideoStreamFoundID,
           fields.MediaInfoBuilder.clazz,
           "onVideoStreamFound", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IJDIIIJ)V"
    );

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onAudioStreamFoundID,
           fields.MediaInfoBuilder.clazz,
           "onAudioStreamFound",
           "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IJLjava/lang/String;IILjava/lang/String;)V"
    );

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onSubtitleStreamFoundID,
           fields.MediaInfoBuilder.clazz,
           "onSubtitleStreamFound", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V"
    );

    GET_ID(GetMethodID,
           fields.MediaInfoBuilder.onChapterFoundID,
           fields.MediaInfoBuilder.clazz,
           "onChapterFound", "(ILjava/lang/String;JJ)V"
    );

    return 0;
}

void utils_fields_free(JavaVM *vm) {
    JNIEnv *env = utils_get_env();
    if (vm == nullptr) {
        return;
    }

    env->DeleteGlobalRef(fields.MediaInfoBuilder.clazz);

    javaVM = nullptr;
}

void utils_call_instance_method_void(JNIEnv *env, jobject instance, jmethodID methodID, ...) {
    va_list args;
    va_start(args, methodID);
    env->CallVoidMethodV(instance, methodID, args);
    va_end(args);
}
