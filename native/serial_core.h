#ifndef SERIAL_CORE_H
#define SERIAL_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Коды ошибок: результат < 0 означает ошибку. */
#define SERIAL_ERR_OPEN    (-1)
#define SERIAL_ERR_IOCTL   (-2)
#define SERIAL_ERR_BAUD    (-3)
#define SERIAL_ERR_IO      (-4)
#define SERIAL_ERR_TIMEOUT (-5)
#define SERIAL_ERR_CLOSED  (-6)
#define SERIAL_ERR_ARG     (-7)

/* Флаги конфигурации — должны совпадать с enum-ами в Kotlin. */
#define SERIAL_PARITY_NONE 0
#define SERIAL_PARITY_ODD  1
#define SERIAL_PARITY_EVEN 2
#define SERIAL_PARITY_MARK 3
#define SERIAL_PARITY_SPACE 4

#define SERIAL_STOP_ONE       0
#define SERIAL_STOP_ONE_POINT_FIVE 1
#define SERIAL_STOP_TWO       2

#define SERIAL_FLOW_NONE     0
#define SERIAL_FLOW_HARDWARE 1
#define SERIAL_FLOW_SOFTWARE 2

typedef struct {
    int baud_rate;
    int data_bits;
    int parity;
    int stop_bits;
    int flow_control;
    int read_timeout_ms;
    int write_timeout_ms;
} serial_config_t;

/*
 * Открыть порт. Возвращает непрозрачный handle (>= 0) либо SERIAL_ERR_*.
 * path: "/dev/ttyUSB0", "COM3" или "\\.\COM10".
 */
intptr_t serial_open(const char *path, const serial_config_t *cfg);

/* Прочитать до len байт. Возвращает число байт, 0 при таймауте, <0 — ошибка. */
int serial_read(intptr_t handle, void *buf, int len, int timeout_ms);

/* Записать len байт. Возвращает число записанных, <0 — ошибка. */
int serial_write(intptr_t handle, const void *buf, int len, int timeout_ms);

/* Сбросить входной и выходной буферы (tcflush/PurgeComm). 0 — успех. */
int serial_flush(intptr_t handle);

/*
 * Дождаться, пока все записанные байты уйдут в линию (tcdrain/FlushFileBuffers).
 * В отличие от serial_flush НЕ выбрасывает данные. 0 — успех.
 */
int serial_drain(intptr_t handle);

/* Закрыть порт. 0 — успех. */
int serial_close(intptr_t handle);

/*
 * Записать список портов в buffer как последовательность C-строк,
 * завершённую дополнительным \0. Возвращает длину записанного (может быть 0).
 * paths: "/dev/ttyUSB0\0/dev/ttyACM0\0\0" или "COM3\0COM5\0\0".
 */
int serial_list(char *buffer, int buffer_len);

/* Текст ошибки по коду. Указатель статический, освобождать не нужно. */
const char *serial_error_text(int code);

#ifdef __cplusplus
}
#endif

#endif /* SERIAL_CORE_H */
