// UAC2 DAC へのアイソクロナス出力(usbdevfs)。OS のミキサーを通さずに PCM を送る。
//
// - 出力エンドポイントへ ISO URB を常に N 本投げておき、完了した URB に次のデータを詰めて再投入する。
// - 非同期モードなので、フィードバックエンドポイント(ISO IN、16.16 固定小数、1 マイクロフレームあたりのフレーム数)
//   を読み、パケットごとのフレーム数を調整する。
// - データが足りないときは無音(ゼロ)を送って、クロックを途切れさせない。
#include <jni.h>
#include <poll.h>
#include <time.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <mutex>
#include <vector>

#define TAG "UsbIso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr int kOutUrbs = 16;
constexpr int kOutPackets = 8;    // 1 URB = 8 パケット(高速 USB・interval 1 で 1ms)。常時 16 本 = 16ms 分
constexpr int kFbUrbs = 2;
constexpr int kFbPackets = 8;
constexpr size_t kRingBytes = 1 << 20;

struct Slot {
    usbdevfs_urb* urb = nullptr;
    uint8_t* buf = nullptr;
    bool isFb = false;
    uint64_t gen = 0;
    uint32_t realFrames = 0;
    bool inflight = false;
};

struct Stream {
    int fd, epOut, epFb, maxPacket, bpf, shift;
    uint64_t nominalQ16;   // 1 パケットあたりのフレーム数(16.16)
    uint64_t curQ16;
    uint64_t acc = 0;

    std::vector<Slot> slots;

    std::mutex mu;         // ring / gen / paused を守る
    uint8_t* ring;
    size_t head = 0, tail = 0;  // 単調増加するバイト位置
    uint64_t gen = 0;
    bool paused = true;

    std::atomic<bool> running{false};
    std::atomic<int64_t> framesPlayed{0};
    std::atomic<int> underruns{0};
    std::atomic<int> errors{0};
    uint64_t outDone = 0, fbDone = 0;  // run スレッドだけが触る(診断用)
    uint32_t lastStart = 0;
};

size_t urbSize(int packets) { return sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc); }

void fillOut(Stream* s, Slot& o) {
    usbdevfs_urb* u = o.urb;
    uint32_t total = 0;
    for (int p = 0; p < kOutPackets; p++) {
        s->acc += s->curQ16;
        uint32_t frames = (uint32_t)(s->acc >> 16);
        s->acc &= 0xFFFF;
        uint32_t len = frames * s->bpf;
        if ((int)len > s->maxPacket) len = (s->maxPacket / s->bpf) * s->bpf;
        u->iso_frame_desc[p].length = len;
        u->iso_frame_desc[p].actual_length = 0;
        u->iso_frame_desc[p].status = 0;
        total += len;
    }
    uint32_t take = 0;
    {
        std::lock_guard<std::mutex> g(s->mu);
        o.gen = s->gen;
        if (!s->paused) {
            size_t avail = s->head - s->tail;
            take = (uint32_t)std::min<size_t>(avail, total);
            take -= take % s->bpf;
            size_t pos = s->tail & (kRingBytes - 1);
            size_t first = std::min<size_t>(take, kRingBytes - pos);
            memcpy(o.buf, s->ring + pos, first);
            if (take > first) memcpy(o.buf + first, s->ring, take - first);
            s->tail += take;
            if (take < total) s->underruns++;
        }
    }
    if (take < total) memset(o.buf + take, 0, total - take);
    o.realFrames = take / s->bpf;

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char)s->epOut;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = o.buf;
    u->buffer_length = (int)total;
    u->actual_length = 0;
    u->start_frame = 0;
    u->number_of_packets = kOutPackets;
    u->error_count = 0;
    u->status = 0;
    u->usercontext = &o;
}

void fillFb(Stream* s, Slot& f) {
    usbdevfs_urb* u = f.urb;
    for (int p = 0; p < kFbPackets; p++) {
        u->iso_frame_desc[p].length = 4;
        u->iso_frame_desc[p].actual_length = 0;
        u->iso_frame_desc[p].status = 0;
    }
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char)s->epFb;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = f.buf;
    u->buffer_length = 4 * kFbPackets;
    u->actual_length = 0;
    u->start_frame = 0;
    u->number_of_packets = kFbPackets;
    u->error_count = 0;
    u->status = 0;
    u->usercontext = &f;
}

bool submit(Stream* s, Slot& sl) {
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, sl.urb) < 0) {
        LOGW("SUBMITURB failed: errno=%d", errno);
        s->errors++;
        return false;
    }
    sl.inflight = true;
    return true;
}

void onFeedback(Stream* s, Slot& f) {
    usbdevfs_urb* u = f.urb;
    size_t off = 0;
    uint32_t latest = 0;
    for (int p = 0; p < kFbPackets; p++) {
        if (u->iso_frame_desc[p].actual_length == 4) {
            memcpy(&latest, f.buf + off, 4);
        }
        off += u->iso_frame_desc[p].length;
    }
    if (latest == 0) return;
    uint64_t perPacket = (uint64_t)latest << s->shift;
    uint64_t lo = s->nominalQ16 * 95 / 100, hi = s->nominalQ16 * 105 / 100;
    if (perPacket >= lo && perPacket <= hi) s->curQ16 = perPacket;
}


int64_t nowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

constexpr int kPollMs = 100;
constexpr int kStallMs = 600;   // 完了がこれだけ来なければ止まったとみなす
constexpr int kMaxRestarts = 5;

// 投入済みの URB が全部返ってくるまで(最大 timeoutMs)回収する。
void reapAll(Stream* s, int timeoutMs) {
    for (int waited = 0; waited <= timeoutMs;) {
        usbdevfs_urb* r = nullptr;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &r) == 0) {
            ((Slot*)r->usercontext)->inflight = false;
            continue;
        }
        if (errno == EINTR) continue;
        bool any = false;
        for (auto& sl : s->slots) any |= sl.inflight;
        if (!any || errno != EAGAIN) return;
        pollfd pfd{s->fd, POLLOUT, 0};
        poll(&pfd, 1, 20);
        waited += 20;
    }
}

// 完了が止まったときの状態をログに出す。
void dumpStall(Stream* s, int restarts) {
    unsigned int speed = 0;
    int sp = ioctl(s->fd, USBDEVFS_GET_SPEED, 0);
    if (sp > 0) speed = (unsigned int)sp;
    LOGW("STALL #%d out=%llu fb=%llu played=%lld lastStart=%u curQ16=%llu speed=%u",
         restarts + 1, (unsigned long long)s->outDone, (unsigned long long)s->fbDone,
         (long long)s->framesPlayed.load(), s->lastStart, (unsigned long long)s->curQ16, speed);
    int i = 0;
    for (auto& sl : s->slots) {
        LOGW("  slot%d fb=%d inflight=%d start_frame=%u status=%d actual=%d errs=%d pkts=%d",
             i++, sl.isFb, sl.inflight, sl.urb->start_frame, sl.urb->status,
             sl.urb->actual_length, sl.urb->error_count, sl.urb->number_of_packets);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_myplayer_usb_UsbIsoNative_create(
        JNIEnv*, jobject, jint fd, jint epOut, jint epFb, jint maxPacket, jint interval,
        jint bytesPerFrame, jint rate) {
    auto* s = new Stream();
    s->fd = fd; s->epOut = epOut; s->epFb = epFb; s->maxPacket = maxPacket;
    s->bpf = bytesPerFrame;
    s->shift = interval > 0 ? interval - 1 : 0;
    // 高速 USB は 1 マイクロフレーム(125µs)ごとに 1 パケット = 8000/2^shift パケット/秒。
    double packetsPerSec = 8000.0 / (double)(1 << s->shift);
    s->nominalQ16 = (uint64_t)((double)rate / packetsPerSec * 65536.0 + 0.5);
    s->curQ16 = s->nominalQ16;
    s->ring = (uint8_t*)malloc(kRingBytes);

    for (int i = 0; i < kOutUrbs + (epFb ? kFbUrbs : 0); i++) {
        Slot sl;
        sl.isFb = i >= kOutUrbs;
        int packets = sl.isFb ? kFbPackets : kOutPackets;
        sl.urb = (usbdevfs_urb*)calloc(1, urbSize(packets));
        sl.buf = (uint8_t*)calloc(1, sl.isFb ? 4 * kFbPackets : (size_t)maxPacket * kOutPackets);
        s->slots.push_back(sl);
    }
    // vector の再配置後のアドレスで usercontext を使うので、ここで確定させてから fill する。
    LOGI("create fd=%d out=0x%x fb=0x%x maxPacket=%d shift=%d bpf=%d rate=%d nominalQ16=%llu",
         fd, epOut, epFb, maxPacket, s->shift, bytesPerFrame, rate, (unsigned long long)s->nominalQ16);
    return (jlong)s;
}

// 呼んだスレッドで、stop() が呼ばれるまで URB を回し続ける。戻り値は 0 = 正常終了、負 = エラー終了。
//
// 完了が一定時間来ない(カーネルが ISO URB を完了させなくなる端末がある)ときは、状態をログに出し、
// 全 URB を取り消して投げ直す。
JNIEXPORT jint JNICALL Java_com_example_myplayer_usb_UsbIsoNative_run(JNIEnv*, jobject, jlong h) {
    auto* s = (Stream*)h;
    s->running = true;
    int result = 0;
    int restarts = 0;
    int64_t lastOutMs = nowMs();  // 最後に OUT の URB が完了した時刻(フィードバックの完了は数えない)

    auto prime = [&]() {
        for (auto& sl : s->slots) {
            if (sl.isFb) fillFb(s, sl); else fillOut(s, sl);
            if (!submit(s, sl)) return false;
        }
        return true;
    };
    if (!prime()) { result = -1; s->running = false; }

    while (s->running) {
        // OUT の完了が止まっていたら、状態を出して全 URB を投げ直す(フィードバックは動き続けることがある)。
        if (nowMs() - lastOutMs > kStallMs) {
            dumpStall(s, restarts);
            if (++restarts > kMaxRestarts) { LOGW("giving up after %d restarts", restarts - 1); result = -5; break; }
            for (auto& sl : s->slots) if (sl.inflight) ioctl(s->fd, USBDEVFS_DISCARDURB, sl.urb);
            reapAll(s, 500);
            s->acc = 0;
            if (!prime()) { result = -6; break; }
            lastOutMs = nowMs();
            continue;
        }
        pollfd pfd{s->fd, POLLOUT, 0};
        int pr = poll(&pfd, 1, kPollMs);
        if (pr < 0 && errno != EINTR) { LOGW("poll failed: errno=%d", errno); result = -2; break; }
        if (pr <= 0) continue;

        usbdevfs_urb* r = nullptr;
        if (ioctl(s->fd, USBDEVFS_REAPURB, &r) < 0) {
            if (errno == EINTR) continue;
            LOGW("REAPURB failed: errno=%d", errno);
            result = -2;
            break;
        }
        auto* sl = (Slot*)r->usercontext;
        sl->inflight = false;
        if (!s->running) break;
        if (r->status != 0 && r->status != -EXDEV) {
            LOGW("URB status=%d (fb=%d)", r->status, sl->isFb);
            if (r->status == -ENODEV || r->status == -ESHUTDOWN || r->status == -EPROTO) { result = -3; break; }
        }
        if (sl->isFb) {
            s->fbDone++;
            onFeedback(s, *sl);
            fillFb(s, *sl);
        } else {
            {
                std::lock_guard<std::mutex> g(s->mu);
                if (sl->gen == s->gen) s->framesPlayed += sl->realFrames;
            }
            // パケット単位のエラー(ログは最初の 20 件まで)。
            if (r->error_count > 0 && s->errors++ < 20) {
                LOGW("iso error_count=%d status=%d first=%d", r->error_count, r->status, r->iso_frame_desc[0].status);
            }
            s->lastStart = r->start_frame;
            s->outDone++;
            lastOutMs = nowMs();
            fillOut(s, *sl);
        }
        if (!submit(s, *sl)) { result = -4; break; }
    }
    s->running = false;
    // 未完了の URB を取り消して回収する。
    for (auto& sl : s->slots) if (sl.inflight) ioctl(s->fd, USBDEVFS_DISCARDURB, sl.urb);
    reapAll(s, 500);
    LOGI("run end result=%d underruns=%d errors=%d restarts=%d", result, s->underruns.load(), s->errors.load(), restarts);
    return result;
}

JNIEXPORT void JNICALL Java_com_example_myplayer_usb_UsbIsoNative_requestStop(JNIEnv*, jobject, jlong h) {
    // run() は REAPURB で待っているので、ここでは取り消しだけ行い、完了通知で起こす。
    auto* s = (Stream*)h;
    s->running = false;
    for (auto& sl : s->slots) if (sl.inflight) ioctl(s->fd, USBDEVFS_DISCARDURB, sl.urb);
}

JNIEXPORT void JNICALL Java_com_example_myplayer_usb_UsbIsoNative_destroy(JNIEnv*, jobject, jlong h) {
    auto* s = (Stream*)h;
    for (auto& sl : s->slots) { free(sl.urb); free(sl.buf); }
    free(s->ring);
    delete s;
}

JNIEXPORT jint JNICALL Java_com_example_myplayer_usb_UsbIsoNative_write(
        JNIEnv* env, jobject, jlong h, jbyteArray data, jint off, jint len) {
    auto* s = (Stream*)h;
    std::lock_guard<std::mutex> g(s->mu);
    size_t free_ = kRingBytes - (s->head - s->tail);
    size_t n = std::min<size_t>((size_t)len, free_);
    n -= n % s->bpf;
    size_t pos = s->head & (kRingBytes - 1);
    size_t first = std::min<size_t>(n, kRingBytes - pos);
    env->GetByteArrayRegion(data, off, (jsize)first, (jbyte*)(s->ring + pos));
    if (n > first) env->GetByteArrayRegion(data, off + (jint)first, (jsize)(n - first), (jbyte*)s->ring);
    s->head += n;
    return (jint)n;
}

JNIEXPORT void JNICALL Java_com_example_myplayer_usb_UsbIsoNative_setPaused(JNIEnv*, jobject, jlong h, jboolean p) {
    auto* s = (Stream*)h;
    std::lock_guard<std::mutex> g(s->mu);
    s->paused = p;
}

JNIEXPORT void JNICALL Java_com_example_myplayer_usb_UsbIsoNative_flush(JNIEnv*, jobject, jlong h) {
    auto* s = (Stream*)h;
    std::lock_guard<std::mutex> g(s->mu);
    s->tail = s->head;
    s->gen++;
    s->framesPlayed = 0;
}

JNIEXPORT jlong JNICALL Java_com_example_myplayer_usb_UsbIsoNative_framesPlayed(JNIEnv*, jobject, jlong h) {
    return ((Stream*)h)->framesPlayed.load();
}

JNIEXPORT jint JNICALL Java_com_example_myplayer_usb_UsbIsoNative_freeBytes(JNIEnv*, jobject, jlong h) {
    auto* s = (Stream*)h;
    std::lock_guard<std::mutex> g(s->mu);
    return (jint)(kRingBytes - (s->head - s->tail));
}

JNIEXPORT jint JNICALL Java_com_example_myplayer_usb_UsbIsoNative_underruns(JNIEnv*, jobject, jlong h) {
    return ((Stream*)h)->underruns.load();
}

}  // extern "C"

extern "C" {

// カーネルのドライバ(snd-usb-audio など)をインターフェースに付け直す(libusb の attach_kernel_driver と同じ)。
// claim でドライバを切り離したままだと、OS の音声出力がこの DAC に出せなくなる。0 以上 = 成功、負 = -errno。
JNIEXPORT jint JNICALL Java_com_example_myplayer_usb_UsbIsoNative_attachKernelDriver(
        JNIEnv*, jobject, jint fd, jint ifno) {
    usbdevfs_ioctl cmd;
    memset(&cmd, 0, sizeof(cmd));
    cmd.ifno = ifno;
    cmd.ioctl_code = USBDEVFS_CONNECT;
    cmd.data = nullptr;
    int r = ioctl(fd, USBDEVFS_IOCTL, &cmd);
    return r < 0 ? -errno : r;
}

}  // extern "C"
