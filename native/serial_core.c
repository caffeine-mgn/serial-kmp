/*
 * serial_core — порт последовательной линии для всех платформ.
 *
 * Один исходник, три ветки:
 *   _WIN32  — Win32 CommAPI (CreateFileW / DCB / COMMTIMEOUTS)
 *   __APPLE__ — termios (iOS/macOS), отличается только набор скоростей
 *   __linux__ — termios
 *
 * Соглашение о ручках: непрозрачный intptr_t. На POSIX это fd, на Windows —
 * HANDLE, приведённый к intptr_t (не 32-битный int: на x64 HANDLE не влезет).
 *
 * Возвраты: 0 — успех, отрицательное — код ошибки (см. SERIAL_ERR_*).
 */
#include "serial_core.h"

#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <termios.h>
#include <unistd.h>
#include <dirent.h>
#endif

/* ---------- POSIX ---------- */

#ifndef _WIN32

static speed_t baud_to_speed(int baud, int *ok) {
    *ok = 1;
    switch (baud) {
        case 110: return B110;
        case 300: return B300;
        case 600: return B600;
        case 1200: return B1200;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
#if defined(B460800)
        case 460800: return B460800;
#endif
#if defined(B500000)
        case 500000: return B500000;
#endif
#if defined(B576000)
        case 576000: return B576000;
#endif
#if defined(B921600)
        case 921600: return B921600;
#endif
#if defined(B1000000)
        case 1000000: return B1000000;
#endif
#if defined(B1152000)
        case 1152000: return B1152000;
#endif
#if defined(B1500000)
        case 1500000: return B1500000;
#endif
#if defined(B2000000)
        case 2000000: return B2000000;
#endif
#if defined(B2500000)
        case 2500000: return B2500000;
#endif
#if defined(B3000000)
        case 3000000: return B3000000;
#endif
#if defined(B3500000)
        case 3500000: return B3500000;
#endif
#if defined(B4000000)
        case 4000000: return B4000000;
#endif
        default:
            *ok = 0;
            return B9600;
    }
}

intptr_t serial_open(const char *path, const serial_config_t *cfg) {
    if (!path || !cfg) return SERIAL_ERR_ARG;

    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) return SERIAL_ERR_OPEN;

    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) {
        close(fd);
        return SERIAL_ERR_IOCTL;
    }

    cfmakeraw(&tio);

    int ok = 0;
    speed_t speed = baud_to_speed(cfg->baud_rate, &ok);
    if (!ok) {
        close(fd);
        return SERIAL_ERR_BAUD;
    }
    if (cfsetispeed(&tio, speed) != 0 || cfsetospeed(&tio, speed) != 0) {
        close(fd);
        return SERIAL_ERR_BAUD;
    }

    tio.c_cflag &= ~CSIZE;
    switch (cfg->data_bits) {
        case 5: tio.c_cflag |= CS5; break;
        case 6: tio.c_cflag |= CS6; break;
        case 7: tio.c_cflag |= CS7; break;
        case 8: tio.c_cflag |= CS8; break;
        default:
            close(fd);
            return SERIAL_ERR_ARG;
    }

    tio.c_cflag |= (CLOCAL | CREAD);

    switch (cfg->parity) {
        case SERIAL_PARITY_NONE:
            tio.c_cflag &= ~(PARENB | PARODD);
            break;
        case SERIAL_PARITY_ODD:
            tio.c_cflag |= PARENB | PARODD;
            break;
        case SERIAL_PARITY_EVEN:
            tio.c_cflag |= PARENB;
            tio.c_cflag &= ~PARODD;
            break;
        case SERIAL_PARITY_MARK:
            tio.c_cflag |= PARENB | PARODD;
            break;
        default:
            close(fd);
            return SERIAL_ERR_ARG;
    }

    if (cfg->stop_bits == SERIAL_STOP_TWO) {
        tio.c_cflag |= CSTOPB;
    } else if (cfg->stop_bits == SERIAL_STOP_ONE) {
        tio.c_cflag &= ~CSTOPB;
    } else {
        /* 1.5 стоп-бита в POSIX termios не выражается */
        close(fd);
        return SERIAL_ERR_ARG;
    }

    switch (cfg->flow_control) {
        case SERIAL_FLOW_HARDWARE:
            tio.c_cflag |= CRTSCTS;
            tio.c_iflag &= ~(IXON | IXOFF);
            break;
        case SERIAL_FLOW_SOFTWARE:
            tio.c_cflag &= ~CRTSCTS;
            tio.c_iflag |= (IXON | IXOFF);
            break;
        default:
            tio.c_cflag &= ~CRTSCTS;
            tio.c_iflag &= ~(IXON | IXOFF);
            break;
    }

    tio.c_cc[VMIN] = 0;
    tio.c_cc[VTIME] = (cc_t) ((cfg->read_timeout_ms / 100) > 255 ? 255 : (cfg->read_timeout_ms / 100));

    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        close(fd);
        return SERIAL_ERR_IOCTL;
    }
    tcflush(fd, TCIOFLUSH);

    return (intptr_t) fd;
}

int serial_read(intptr_t handle, void *buf, int len, int timeout_ms) {
    if (handle < 0 || !buf || len <= 0) return SERIAL_ERR_ARG;
    int fd = (int) handle;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int ready = poll(&pfd, 1, timeout_ms);
    if (ready < 0) {
        if (errno == EINTR) return 0;
        return SERIAL_ERR_IO;
    }
    if (ready == 0) return 0;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return SERIAL_ERR_CLOSED;

    ssize_t n = read(fd, buf, (size_t) len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
        return SERIAL_ERR_IO;
    }
    return (int) n;
}

int serial_write(intptr_t handle, const void *buf, int len, int timeout_ms) {
    if (handle < 0 || !buf || len <= 0) return SERIAL_ERR_ARG;
    int fd = (int) handle;

    int total = 0;
    while (total < len) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return SERIAL_ERR_IO;
        }
        if (ready == 0) return SERIAL_ERR_TIMEOUT;
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return SERIAL_ERR_CLOSED;

        ssize_t n = write(fd, (const char *) buf + total, (size_t) (len - total));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            return SERIAL_ERR_IO;
        }
        total += (int) n;
    }
    return total;
}

int serial_flush(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return tcflush((int) handle, TCIOFLUSH) == 0 ? 0 : SERIAL_ERR_IO;
}

int serial_drain(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return tcdrain((int) handle) == 0 ? 0 : SERIAL_ERR_IO;
}

int serial_close(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return close((int) handle) == 0 ? 0 : SERIAL_ERR_IO;
}

#else /* _WIN32 */

/* ---------- Windows ---------- */

static DWORD parity_to_win(int parity) {
    switch (parity) {
        case SERIAL_PARITY_ODD: return ODDPARITY;
        case SERIAL_PARITY_EVEN: return EVENPARITY;
        case SERIAL_PARITY_MARK: return MARKPARITY;
        default: return NOPARITY;
    }
}

intptr_t serial_open(const char *path, const serial_config_t *cfg) {
    if (!path || !cfg) return SERIAL_ERR_ARG;

    char full[256];
    if (path[0] == '\\' && path[1] == '\\') {
        strncpy(full, path, sizeof(full) - 1);
        full[sizeof(full) - 1] = '\0';
    } else {
        snprintf(full, sizeof(full), "\\\\.\\%s", path);
    }

    HANDLE h = CreateFileA(full, GENERIC_READ | GENERIC_WRITE, 0, NULL,
                           OPEN_EXISTING, FILE_FLAG_OVERLAPPED, NULL);
    if (h == INVALID_HANDLE_VALUE) return SERIAL_ERR_OPEN;

    DCB dcb;
    memset(&dcb, 0, sizeof(dcb));
    dcb.DCBlength = sizeof(dcb);
    if (!GetCommState(h, &dcb)) {
        CloseHandle(h);
        return SERIAL_ERR_IOCTL;
    }

    dcb.BaudRate = (DWORD) cfg->baud_rate;
    dcb.ByteSize = (BYTE) cfg->data_bits;
    dcb.Parity = (BYTE) parity_to_win(cfg->parity);
    dcb.fParity = cfg->parity != SERIAL_PARITY_NONE;
    dcb.StopBits = cfg->stop_bits == SERIAL_STOP_TWO ? TWOSTOPBITS
                 : cfg->stop_bits == SERIAL_STOP_ONE ? ONESTOPBIT
                 : ONE5STOPBITS;
    dcb.fBinary = TRUE;
    dcb.fOutxCtsFlow = cfg->flow_control == SERIAL_FLOW_HARDWARE;
    dcb.fRtsControl = cfg->flow_control == SERIAL_FLOW_HARDWARE ? RTS_CONTROL_HANDSHAKE : RTS_CONTROL_ENABLE;
    dcb.fOutX = cfg->flow_control == SERIAL_FLOW_SOFTWARE;
    dcb.fInX = cfg->flow_control == SERIAL_FLOW_SOFTWARE;

    if (!SetCommState(h, &dcb)) {
        CloseHandle(h);
        return SERIAL_ERR_IOCTL;
    }

    COMMTIMEOUTS to;
    memset(&to, 0, sizeof(to));
    to.ReadIntervalTimeout = (DWORD) cfg->read_timeout_ms;
    to.ReadTotalTimeoutConstant = (DWORD) cfg->read_timeout_ms;
    to.WriteTotalTimeoutConstant = (DWORD) cfg->write_timeout_ms;
    if (!SetCommTimeouts(h, &to)) {
        CloseHandle(h);
        return SERIAL_ERR_IOCTL;
    }

    PurgeComm(h, PURGE_RXCLEAR | PURGE_TXCLEAR);
    return (intptr_t) h;
}

int serial_read(intptr_t handle, void *buf, int len, int timeout_ms) {
    if (handle < 0 || !buf || len <= 0) return SERIAL_ERR_ARG;
    HANDLE h = (HANDLE) handle;

    OVERLAPPED ov;
    memset(&ov, 0, sizeof(ov));
    ov.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);

    DWORD got = 0;
    int result;
    if (!ReadFile(h, buf, (DWORD) len, &got, &ov)) {
        if (GetLastError() == ERROR_IO_PENDING) {
            DWORD wait = WaitForSingleObject(ov.hEvent, (DWORD) timeout_ms);
            if (wait == WAIT_TIMEOUT) {
                CancelIoEx(h, &ov);
                CloseHandle(ov.hEvent);
                return 0;
            }
            if (!GetOverlappedResult(h, &ov, &got, FALSE)) {
                CloseHandle(ov.hEvent);
                return SERIAL_ERR_IO;
            }
            result = (int) got;
        } else {
            CloseHandle(ov.hEvent);
            return SERIAL_ERR_IO;
        }
    } else {
        result = (int) got;
    }

    CloseHandle(ov.hEvent);
    return result;
}

int serial_write(intptr_t handle, const void *buf, int len, int timeout_ms) {
    if (handle < 0 || !buf || len <= 0) return SERIAL_ERR_ARG;
    HANDLE h = (HANDLE) handle;

    int total = 0;
    while (total < len) {
        OVERLAPPED ov;
        memset(&ov, 0, sizeof(ov));
        ov.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);
        if (!ov.hEvent) return SERIAL_ERR_IO;

        DWORD written = 0;
        BOOL ok = WriteFile(h, (const char *) buf + total, (DWORD) (len - total), &written, &ov);
        if (!ok) {
            if (GetLastError() == ERROR_IO_PENDING) {
                DWORD wait = WaitForSingleObject(ov.hEvent, (DWORD) timeout_ms);
                if (wait == WAIT_TIMEOUT) {
                    CancelIoEx(h, &ov);
                    CloseHandle(ov.hEvent);
                    return SERIAL_ERR_TIMEOUT;
                }
                if (!GetOverlappedResult(h, &ov, &written, FALSE)) {
                    CloseHandle(ov.hEvent);
                    return SERIAL_ERR_IO;
                }
            } else {
                CloseHandle(ov.hEvent);
                return SERIAL_ERR_IO;
            }
        }
        CloseHandle(ov.hEvent);

        if (written == 0) return SERIAL_ERR_TIMEOUT;
        total += (int) written;
    }
    return total;
}

int serial_flush(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return PurgeComm((HANDLE) handle, PURGE_RXCLEAR | PURGE_TXCLEAR) ? 0 : SERIAL_ERR_IO;
}

int serial_drain(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return FlushFileBuffers((HANDLE) handle) ? 0 : SERIAL_ERR_IO;
}

int serial_close(intptr_t handle) {
    if (handle < 0) return SERIAL_ERR_ARG;
    return CloseHandle((HANDLE) handle) ? 0 : SERIAL_ERR_IO;
}

#endif /* _WIN32 */

/* ---------- общее: список портов ---------- */

int serial_list(char *buffer, int buffer_len) {
    if (!buffer || buffer_len <= 0) return SERIAL_ERR_ARG;
    buffer[0] = '\0';

#ifdef _WIN32
    HKEY key;
    if (RegOpenKeyExA(HKEY_LOCAL_MACHINE, "HARDWARE\\DEVICEMAP\\SERIALCOMM",
                      0, KEY_READ, &key) != ERROR_SUCCESS) {
        return 0;
    }

    int offset = 0;
    DWORD index = 0;
    while (1) {
        char name[256];
        DWORD name_len = sizeof(name);
        BYTE data[256];
        DWORD data_len = sizeof(data);
        DWORD type = 0;

        LONG rc = RegEnumValueA(key, index, name, &name_len, NULL, &type, data, &data_len);
        if (rc != ERROR_SUCCESS) break;

        const char *port = (const char *) data;
        int needed = (int) strlen(port) + 1;
        if (offset + needed >= buffer_len) break;
        memcpy(buffer + offset, port, (size_t) needed);
        offset += needed;
        index++;
    }
    RegCloseKey(key);
    return offset;

#else
    int offset = 0;

    /* 1. /dev/serial/by-id — символические ссылки с осмысленными именами */
    DIR *by_id = opendir("/dev/serial/by-id");
    if (by_id) {
        struct dirent *entry;
        while ((entry = readdir(by_id)) != NULL) {
            if (entry->d_name[0] == '.') continue;
            char link_path[512];
            snprintf(link_path, sizeof(link_path), "/dev/serial/by-id/%s", entry->d_name);

            char target[512];
            ssize_t len = readlink(link_path, target, sizeof(target) - 1);
            if (len <= 0) continue;
            target[len] = '\0';

            const char *base = strrchr(target, '/');
            base = base ? base + 1 : target;

            char full[600];
            snprintf(full, sizeof(full), "/dev/%s", base);
            int needed = (int) strlen(full) + 1;
            if (offset + needed >= buffer_len) break;
            memcpy(buffer + offset, full, (size_t) needed);
            offset += needed;
        }
        closedir(by_id);
    }

    /* 2. /dev — прямой перебор известных префиксов */
    static const char *PREFIXES[] = {"ttyUSB", "ttyACM", "ttyS", "ttyAMA", "ttymxc", "rfcomm", "cu."};
    const int PREFIX_COUNT = (int) (sizeof(PREFIXES) / sizeof(PREFIXES[0]));

    DIR *dev = opendir("/dev");
    if (dev) {
        struct dirent *entry;
        while ((entry = readdir(dev)) != NULL) {
            const char *name = entry->d_name;
            if (name[0] == '.') continue;

            int matches = 0;
            for (int i = 0; i < PREFIX_COUNT; i++) {
                size_t plen = strlen(PREFIXES[i]);
                if (strncmp(name, PREFIXES[i], plen) == 0) {
                    matches = 1;
                    break;
                }
            }
            if (!matches) continue;

            char full[600];
            snprintf(full, sizeof(full), "/dev/%s", name);

            /* уже добавлен из by-id? */
            if (offset > 0 && strstr(buffer, full) != NULL) continue;

            int needed = (int) strlen(full) + 1;
            if (offset + needed >= buffer_len) break;
            memcpy(buffer + offset, full, (size_t) needed);
            offset += needed;
        }
        closedir(dev);
    }

    return offset;
#endif
}

const char *serial_error_text(int code) {
    switch (code) {
        case SERIAL_ERR_OPEN: return "cannot open port";
        case SERIAL_ERR_IOCTL: return "cannot configure port";
        case SERIAL_ERR_BAUD: return "unsupported baud rate";
        case SERIAL_ERR_IO: return "I/O error";
        case SERIAL_ERR_TIMEOUT: return "timeout";
        case SERIAL_ERR_CLOSED: return "port closed";
        case SERIAL_ERR_ARG: return "invalid argument";
        default: return "unknown error";
    }
}
