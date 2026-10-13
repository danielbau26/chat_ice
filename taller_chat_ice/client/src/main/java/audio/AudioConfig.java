package audio;

import javax.sound.sampled.AudioFormat;

/**
 * Configuración del audio de las llamadas (compartida por emisor y receptor).
 *
 * Ambos lados DEBEN usar exactamente el mismo formato; si no, el audio se escucha
 * acelerado, lento o como ruido.
 *
 * Formato elegido: PCM 16 kHz, 16 bits, mono.
 *  - 16 kHz alcanza para voz con buena calidad (calidad "telefónica mejorada").
 *  - Ancho de banda: 16000 muestras/s * 2 bytes = 32 KB/s por persona, sin compresión.
 */
public final class AudioConfig {

    private AudioConfig() {
    }

    /** Muestras por segundo */
    public static final float SAMPLE_RATE = 16000f;

    /** Bits por muestra */
    public static final int SAMPLE_SIZE_BITS = 16;

    /** 1 = mono */
    public static final int CHANNELS = 1;

    /** PCM con signo, little-endian (orden de bytes estándar en Windows/Java Sound) */
    public static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);

    /** Cada datagrama lleva 20 ms de audio: buen equilibrio entre latencia y número de paquetes */
    public static final int FRAME_MS = 20;

    /** Bytes de audio en 20 ms: 16000 * 0.020 = 320 muestras * 2 bytes = 640 bytes */
    public static final int FRAME_BYTES = (int) (SAMPLE_RATE * FRAME_MS / 1000) * (SAMPLE_SIZE_BITS / 8) * CHANNELS;

    /**
     * Cabecera de cada datagrama: número de secuencia (int, 4 bytes).
     * Sirve para descartar paquetes que llegan tarde o desordenados (UDP no garantiza orden).
     */
    public static final int HEADER_BYTES = 4;

    /** Tamaño total de un datagrama de audio */
    public static final int PACKET_BYTES = HEADER_BYTES + FRAME_BYTES;

    /**
     * Máximo de paquetes en espera por cada persona que habla (10 * 20 ms = 200 ms).
     * Si se acumulan más, se descartan los más viejos para no aumentar el retraso.
     */
    public static final int MAX_QUEUED_FRAMES = 10;

    /** Buffer de las líneas de audio (micrófono y parlantes): 8 frames = 160 ms */
    public static final int LINE_BUFFER_BYTES = FRAME_BYTES * 8;

    /** Si una persona deja de enviar audio por este tiempo, se deja de mezclar */
    public static final long SOURCE_TIMEOUT_MS = 2000;
}
