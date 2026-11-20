package org.carlife.hulab;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Acoustic echo canceller for the CarLife head-unit microphone (16 kHz mono PCM16).
 *
 * Why it exists: during a Tbox Bluetooth phone call routed over CarLife USB, the far-end
 * voice is played on the car speakers and picked up again by the head-unit microphone.
 * The stock sdsdk816 WebRTC AECM cannot cancel it (its fixed delay hint is tuned for a
 * local speaker, not for the USB -> head unit -> speaker -> mic -> USB round trip).
 * Here both signals are available exactly: the far-end reference is the MEDIA-lane PCM
 * written to USB, the near-end is the 0x58001 mic PCM coming back.
 *
 * Pipeline per 8 ms block (N=128 samples):
 *  1. Time alignment: reference samples are placed on a 16 kHz timeline by USB write time,
 *     microphone samples by USB arrival time (continuous streams stay sample-contiguous).
 *  2. Coarse delay estimation (binary-spectrum matching, up to 800 ms) selects a bulk delay.
 *  3. Partitioned-block frequency-domain NLMS (overlap-save, 24 x 8 ms = 192 ms tail).
 *  4. Coherence-based residual echo suppression (WebRTC AEC2-style NLP, WOLA sqrt-Hann).
 *
 * Thread model: pushRef() runs on the USB writer thread, process() on a single mic worker
 * thread. They only share the reference ring (guarded by refLock).
 */
final class EchoCanceller implements AutoCloseable {
    static final int RATE = 16000;
    static final int N = 128;
    static final int M = 2 * N;
    static final int BINS = N + 1;
    static final int SAMPLES_PER_MS = RATE / 1000;
    static final int MAX_LAG_BLOCKS = 100;

    private static final int REF_RING = 1 << 15;          // 2.048 s
    private static final int JITTER_AHEAD = 100 * SAMPLES_PER_MS;
    private static final int JITTER_BEHIND = 500 * SAMPLES_PER_MS;
    private static final int PRE_MARGIN_BLOCKS = 3;
    private static final float MU = tun("aec.mu", 0.5f);
    private static final float ERR_CLIP = tun("aec.clip", 0.5f);
    private static final float DELTA = 1e7f;
    private static final float ACTIVE_ENERGY = N * 100f * 100f;   // ~ -50 dBFS block energy
    private static final int DEC_TAPS = 48;
    private static final int DELAY_BANDS = 32;
    private static final int DELAY_FIRST_BIN = 6;
    private static final int NLP_LO = 8, NLP_HI = 40;
    private static final long DUMP_CAP_BYTES = 12L << 20;   // per stream, ~6.5 min @ 16 kHz

    /** Delay learned in the previous call (process lifetime), reused as the next starting point. */
    private static volatile int learnedBulkBlocks = -1;

    private final int partitions;
    private final int manualBulkBlocks;
    private final int nlpLevel;
    private final float overDrive, gainFloor;
    private final Fft fft = new Fft(M);
    private final float[] window = new float[M];

    // ---------------- reference timeline (shared with writer thread) ----------------
    private final Object refLock = new Object();
    private final float[] refRing = new float[REF_RING];
    private long refEnd = Long.MIN_VALUE, refValidFrom = Long.MAX_VALUE;
    private long refChunks, refResyncs;
    private final float[] decTaps = new float[DEC_TAPS];
    private float[] decBuf = new float[DEC_TAPS - 1 + 4096];
    private int decLen = DEC_TAPS - 1;
    private volatile boolean closed;

    // ---------------- microphone timeline ----------------
    private long micEnd = Long.MIN_VALUE;
    private boolean gapPending;
    private final float[] inBlock = new float[N];
    private int inFill;
    private long inBlockStart;
    private long micResyncs;
    private short[] outFifo = new short[4096];
    private int outLen;

    // ---------------- adaptive filter ----------------
    private int bulkBlocks;
    private final float[][] xRe, xIm, wRe, wIm, fgRe, fgIm, xPowPart;
    private double bgErrSm, fgErrSm, micSm, yEchoSm, errSm;
    private static final float RATE_FLOOR = tun("aec.rate_floor", 0.1f);
    private long fgCopies, bgRestores;
    private final float[] xPowSum = new float[BINS];
    private int head = -1;
    private boolean rebuildHistory = true;
    private long blocks;
    private int peakPartition;
    private int divergeStreak;
    private long filterResets;

    // ---------------- NLP ----------------
    private final float[] sd = new float[BINS], se = new float[BINS], sx = new float[BINS];
    private final float[] sdeRe = new float[BINS], sdeIm = new float[BINS];
    private final float[] sxdRe = new float[BINS], sxdIm = new float[BINS];
    private final float[] gainSm = new float[BINS];
    private final float[] ola = new float[N];
    private final float[] dPrev = new float[N], ePrev = new float[N];
    private boolean nearState;

    // ---------------- delay estimator ----------------
    private final float[] farMean = new float[DELAY_BANDS], nearMean = new float[DELAY_BANDS];
    private final int[] farHist = new int[MAX_LAG_BLOCKS];
    private final boolean[] farActiveHist = new boolean[MAX_LAG_BLOCKS];
    private final float[] lagCost = new float[MAX_LAG_BLOCKS];
    private final int[] lagCount = new int[MAX_LAG_BLOCKS];
    private int farHistHead;
    private int candidateLag = -1, candidateStreak;
    private int estimatedLag = -1;
    private long delayChanges;

    // ---------------- scratch ----------------
    private final float[] frame = new float[M], tmp = new float[M];
    private final float[] d = new float[N], e = new float[N], y = new float[N];
    private final float[] yRe = new float[BINS], yIm = new float[BINS];
    private final float[] e2 = new float[N];
    private final float[] eRe = new float[BINS], eIm = new float[BINS];
    private final float[] dRe = new float[BINS], dIm = new float[BINS];
    private final float[] fxRe = new float[BINS], fxIm = new float[BINS];
    private final float[] gRe = new float[BINS], gIm = new float[BINS];
    private final float[] fre = new float[M], fim = new float[M];

    // ---------------- stats ----------------
    private double statD, statE;
    private long statFarBlocks, statBlocks, statSuppressedBlocks, statCpuNs, statOutD;
    private double erleDb = Double.NaN;

    // ---------------- optional PCM dump ----------------
    private OutputStream dumpMic, dumpRef, dumpOut;
    private long dumpBytes;

    /**
     * @param partitions      adaptive filter length in 8 ms partitions
     * @param manualDelayMs   fixed bulk delay in ms, or negative for automatic estimation
     * @param nlpLevel        0 = linear filter only, 1 = mild, 2 = normal, 3 = strong suppression
     * @param initialDelayMs  starting bulk delay when nothing has been learned yet
     * @param dumpDir         directory for mic/ref/out PCM dumps, or null
     */
    EchoCanceller(int partitions, int manualDelayMs, int nlpLevel, int initialDelayMs, File dumpDir) {
        this.partitions = Math.max(4, Math.min(64, partitions));
        this.manualBulkBlocks = manualDelayMs < 0 ? -1 : Math.min(MAX_LAG_BLOCKS - 1, msToBlocks(manualDelayMs));
        this.nlpLevel = Math.max(0, Math.min(3, nlpLevel));
        this.overDrive = this.nlpLevel <= 1 ? 1f : this.nlpLevel == 2 ? 2f : 4f;
        this.gainFloor = this.nlpLevel <= 1 ? 0.1f : this.nlpLevel == 2 ? 0.03f : 0.01f;
        int learned = learnedBulkBlocks;
        if (manualBulkBlocks >= 0) bulkBlocks = manualBulkBlocks;
        else if (learned >= 0) bulkBlocks = learned;
        else bulkBlocks = Math.max(0, msToBlocks(initialDelayMs) - PRE_MARGIN_BLOCKS);
        xRe = new float[this.partitions][BINS];
        xIm = new float[this.partitions][BINS];
        wRe = new float[this.partitions][BINS];
        wIm = new float[this.partitions][BINS];
        fgRe = new float[this.partitions][BINS];
        fgIm = new float[this.partitions][BINS];
        xPowPart = new float[this.partitions][BINS];
        for (int i = 0; i < M; i++) {
            window[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / M));
        }
        designDecimator();
        Arrays.fill(gainSm, 1f);
        Arrays.fill(farMean, 1f);
        Arrays.fill(nearMean, 1f);
        Arrays.fill(lagCost, DELAY_BANDS / 2f);
        if (dumpDir != null) openDump(dumpDir);
    }

    private static float tun(String k, float def) {
        try { String v = System.getProperty(k); return v == null ? def : Float.parseFloat(v); } catch (Throwable t) { return def; }
    }

    private static int msToBlocks(int ms) {
        return Math.max(0, Math.round(ms * SAMPLES_PER_MS / (float) N));
    }

    // =====================================================================
    // Reference input (USB writer thread)
    // =====================================================================

    /** Far-end PCM exactly as written to the head unit: 48 kHz stereo PCM16 little endian. */
    void pushRef48kStereo(byte[] pcm, long writeUptimeMs) {
        if (pcm == null || pcm.length < 4) return;
        synchronized (refLock) {
            if (closed) return;
            int n = decimate(pcm);
            if (n <= 0) return;
            long nominal = writeUptimeMs * SAMPLES_PER_MS - n;
            long start;
            if (refEnd == Long.MIN_VALUE || nominal - refEnd > JITTER_AHEAD || refEnd - nominal > JITTER_BEHIND) {
                start = nominal;
                if (refEnd != Long.MIN_VALUE && start > refEnd && start - refEnd < REF_RING) {
                    for (long a = refEnd; a < start; a++) refRing[(int) (a & (REF_RING - 1))] = 0f;
                } else {
                    refValidFrom = start;
                }
                if (refEnd != Long.MIN_VALUE) refResyncs++;
            } else {
                start = refEnd;
            }
            for (int i = 0; i < n; i++) refRing[(int) ((start + i) & (REF_RING - 1))] = decOut[i];
            refEnd = start + n;
            refChunks++;
        }
    }

    private float[] decOut = new float[2048];

    private void designDecimator() {
        double fc = 7000.0 / 48000.0;
        double sum = 0;
        double mid = (DEC_TAPS - 1) / 2.0;
        for (int t = 0; t < DEC_TAPS; t++) {
            double x = t - mid;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * t / (DEC_TAPS - 1)) + 0.08 * Math.cos(4 * Math.PI * t / (DEC_TAPS - 1));
            decTaps[t] = (float) (sinc * w);
            sum += decTaps[t];
        }
        for (int t = 0; t < DEC_TAPS; t++) decTaps[t] /= (float) sum;
    }

    /** Stereo 48 kHz -> mono 16 kHz into decOut; returns the number of output samples. */
    private int decimate(byte[] pcm) {
        int frames = pcm.length / 4;
        if (decLen + frames > decBuf.length) decBuf = Arrays.copyOf(decBuf, decLen + frames + 1024);
        for (int i = 0; i < frames; i++) {
            int o = i * 4;
            int l = (short) ((pcm[o] & 0xff) | (pcm[o + 1] << 8));
            int r = (short) ((pcm[o + 2] & 0xff) | (pcm[o + 3] << 8));
            decBuf[decLen++] = (l + r) * 0.5f;
        }
        int outCount = Math.max(0, (decLen - (DEC_TAPS - 1)) / 3);
        if (decOut.length < outCount) decOut = new float[outCount + 256];
        for (int m = 0; m < outCount; m++) {
            int end = m * 3 + DEC_TAPS - 1;
            float acc = 0f;
            for (int t = 0; t < DEC_TAPS; t++) acc += decTaps[t] * decBuf[end - t];
            decOut[m] = acc;
        }
        int consumed = outCount * 3;
        System.arraycopy(decBuf, consumed, decBuf, 0, decLen - consumed);
        decLen -= consumed;
        return outCount;
    }

    private void readRef(long index, float[] dst, int off, int n) {
        synchronized (refLock) {
            long lo = Math.max(refValidFrom, refEnd - REF_RING);
            for (int i = 0; i < n; i++) {
                long a = index + i;
                dst[off + i] = (a >= lo && a < refEnd) ? refRing[(int) (a & (REF_RING - 1))] : 0f;
            }
        }
    }

    // =====================================================================
    // Microphone processing (mic worker thread)
    // =====================================================================

    /** Signals that microphone samples were dropped before process(); the next chunk re-anchors. */
    void markGap() {
        gapPending = true;
    }

    /**
     * Processes head-unit mic PCM16 (16 kHz mono) that arrived over USB at arrivalUptimeMs.
     * Returns echo-cancelled PCM16; the length may differ from the input (fixed 8-16 ms latency).
     */
    byte[] process(byte[] pcm, long arrivalUptimeMs) {
        int n = pcm.length / 2;
        if (n == 0) return new byte[0];
        long nominal = arrivalUptimeMs * SAMPLES_PER_MS - n;
        if (micEnd == Long.MIN_VALUE || gapPending || Math.abs(nominal - micEnd) > JITTER_AHEAD) {
            if (micEnd != Long.MIN_VALUE) micResyncs++;
            gapPending = false;
            // Flush a partial block unprocessed so the output sample count stays continuous.
            for (int i = 0; i < inFill; i++) pushOut(inBlock[i]);
            inFill = 0;
            micEnd = nominal;
        }
        for (int i = 0; i < n; i++) {
            if (inFill == 0) inBlockStart = micEnd;
            inBlock[inFill++] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            micEnd++;
            if (inFill == N) {
                long t0 = System.nanoTime();
                processBlock(inBlockStart);
                statCpuNs += System.nanoTime() - t0;
                inFill = 0;
            }
        }
        byte[] out = new byte[outLen * 2];
        for (int i = 0; i < outLen; i++) {
            out[2 * i] = (byte) outFifo[i];
            out[2 * i + 1] = (byte) (outFifo[i] >> 8);
        }
        outLen = 0;
        return out;
    }

    private void pushOut(float v) {
        if (outLen == outFifo.length) outFifo = Arrays.copyOf(outFifo, outFifo.length * 2);
        int s = Math.round(v);
        outFifo[outLen++] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
    }

    private void processBlock(long j0) {
        blocks++;
        statBlocks++;
        System.arraycopy(inBlock, 0, d, 0, N);
        final int K = partitions;
        long r0 = j0 - (long) bulkBlocks * N;

        // ---- far-end partition spectra (newest at head) ----
        head = (head + 1) % K;
        if (rebuildHistory) {
            rebuildHistory = false;
            Arrays.fill(xPowSum, 0f);
            // Oldest first so `frame` ends up holding the newest partition (used for xE below).
            for (int k = K - 1; k >= 0; k--) {
                int slot = (head - k + K) % K;
                readRef(r0 - (long) k * N - N, frame, 0, M);
                rfft(frame, xRe[slot], xIm[slot]);
                for (int f = 0; f < BINS; f++) {
                    float p = xRe[slot][f] * xRe[slot][f] + xIm[slot][f] * xIm[slot][f];
                    xPowPart[slot][f] = p;
                    xPowSum[f] += p;
                }
            }
        } else {
            readRef(r0 - N, frame, 0, M);
            rfft(frame, xRe[head], xIm[head]);
            for (int f = 0; f < BINS; f++) {
                float p = xRe[head][f] * xRe[head][f] + xIm[head][f] * xIm[head][f];
                xPowSum[f] += p - xPowPart[head][f];
                if (xPowSum[f] < 0f) xPowSum[f] = 0f;
                xPowPart[head][f] = p;
            }
        }

        double xE = 0;
        for (int i = N; i < M; i++) xE += frame[i] * frame[i];

        // ---- background (adapting) and foreground (output) echo estimates ----
        echoEstimate(wRe, wIm, y);
        double dE = 0, eE = 0, fE = 0, yE = 0;
        for (int i = 0; i < N; i++) {
            e[i] = d[i] - y[i];
            dE += d[i] * d[i];
            eE += e[i] * e[i];
            yE += y[i] * y[i];
        }
        // Speex-style rate control: once the filter models echo, shrink the step when the error is
        // dominated by something it cannot model (near-end speech / double talk).
        yEchoSm = 0.8 * yEchoSm + 0.2 * yE;
        errSm = 0.8 * errSm + 0.2 * eE;
        float rate = 1f;
        if (fgCopies > 0 && errSm > 0) {
            rate = (float) Math.min(1.0, Math.max(RATE_FLOOR, 2.0 * yEchoSm / errSm));
        }
        echoEstimate(fgRe, fgIm, tmp);
        for (int i = 0; i < N; i++) {
            e2[i] = d[i] - tmp[i];
            fE += e2[i] * e2[i];
        }

        // ---- background NLMS update with error clipping ----
        Arrays.fill(frame, 0, N, 0f);
        System.arraycopy(e, 0, frame, N, N);
        rfft(frame, eRe, eIm);
        for (int f = 0; f < BINS; f++) {
            float denom = xPowSum[f] + DELTA;
            float er = eRe[f], ei = eIm[f];
            float mag = (float) Math.sqrt((er * er + ei * ei) / denom);
            if (mag > ERR_CLIP) {
                float s = ERR_CLIP / mag;
                er *= s;
                ei *= s;
            }
            float step = MU * rate / denom;
            gRe[f] = er * step;
            gIm[f] = ei * step;
        }
        int constrain = (int) (blocks % K);
        for (int k = 0; k < K; k++) {
            int slot = (head - k + K) % K;
            float[] wr = wRe[k], wi = wIm[k], br = xRe[slot], bi = xIm[slot];
            for (int f = 0; f < BINS; f++) {
                // W += conj(X) * G
                wr[f] += br[f] * gRe[f] + bi[f] * gIm[f];
                wi[f] += br[f] * gIm[f] - bi[f] * gRe[f];
            }
            if (k == constrain) {
                irfft(wr, wi, tmp);
                Arrays.fill(tmp, N, M, 0f);
                rfft(tmp, wr, wi);
            }
        }

        // ---- two-path control: promote a better background, roll back a diverged one ----
        boolean farActive = xE > ACTIVE_ENERGY;
        bgErrSm = 0.85 * bgErrSm + 0.15 * eE;
        fgErrSm = 0.85 * fgErrSm + 0.15 * fE;
        micSm = 0.85 * micSm + 0.15 * dE;
        if (farActive && micSm > ACTIVE_ENERGY) {
            if (bgErrSm < 0.6 * fgErrSm && bgErrSm < 0.5 * micSm) {
                copyFilter(wRe, wIm, fgRe, fgIm);
                fgCopies++;
                fgErrSm = bgErrSm;
            } else if (fgErrSm < 0.25 * bgErrSm) {
                copyFilter(fgRe, fgIm, wRe, wIm);
                bgRestores++;
                bgErrSm = fgErrSm;
            }
        }
        if (dE > ACTIVE_ENERGY && eE > 8 * dE) {
            if (++divergeStreak >= 20) resetFilter();
        } else {
            divergeStreak = 0;
        }
        float[] lin = e2;
        double linE = fE;
        if (fE > dE) {
            lin = d;
            linE = dE;
            statOutD++;
        }
        if ((blocks & 15) == 0) updatePeakPartition();

        if (farActive) {
            statFarBlocks++;
            if (!nearState) {
                statD += dE;
                statE += linE;
            }
        }

        // ---- near-end spectrum (shared by NLP and delay estimator) ----
        for (int i = 0; i < N; i++) {
            frame[i] = dPrev[i] * window[i];
            frame[N + i] = d[i] * window[N + i];
        }
        rfft(frame, dRe, dIm);

        // ---- residual echo suppression + output ----
        if (nlpLevel == 0) {
            for (int i = 0; i < N; i++) pushOut(lin[i]);
        } else {
            nlp(lin, r0, farActive);
        }
        System.arraycopy(d, 0, dPrev, 0, N);
        System.arraycopy(lin, 0, ePrev, 0, N);

        // ---- delay estimation ----
        // Before the first estimate the filter is misaligned, which makes the NLP read echo as
        // near-end speech; gate on energy only until a delay has been found.
        if (manualBulkBlocks < 0) estimateDelay(j0, dE > ACTIVE_ENERGY && (estimatedLag < 0 || !nearState));
        writeDump(r0);
    }

    private void echoEstimate(float[][] wr, float[][] wi, float[] out) {
        final int K = partitions;
        Arrays.fill(yRe, 0f);
        Arrays.fill(yIm, 0f);
        for (int k = 0; k < K; k++) {
            int slot = (head - k + K) % K;
            float[] ar = wr[k], ai = wi[k], br = xRe[slot], bi = xIm[slot];
            for (int f = 0; f < BINS; f++) {
                yRe[f] += ar[f] * br[f] - ai[f] * bi[f];
                yIm[f] += ar[f] * bi[f] + ai[f] * br[f];
            }
        }
        irfft(yRe, yIm, frame);
        System.arraycopy(frame, N, out, 0, N);
    }

    private void copyFilter(float[][] srcRe, float[][] srcIm, float[][] dstRe, float[][] dstIm) {
        for (int k = 0; k < partitions; k++) {
            System.arraycopy(srcRe[k], 0, dstRe[k], 0, BINS);
            System.arraycopy(srcIm[k], 0, dstIm[k], 0, BINS);
        }
    }

    private void nlp(float[] lin, long r0, boolean farActive) {
        for (int i = 0; i < N; i++) {
            frame[i] = ePrev[i] * window[i];
            frame[N + i] = lin[i] * window[N + i];
        }
        rfft(frame, eRe, eIm);
        readRef(r0 - (long) peakPartition * N - N, frame, 0, M);
        for (int i = 0; i < M; i++) frame[i] *= window[i];
        rfft(frame, fxRe, fxIm);

        final float a = 0.9f, b = 0.1f;
        double deSum = 0, xdSum = 0;
        float sxBand = 0f;
        for (int f = 0; f < BINS; f++) {
            float dr = dRe[f], di = dIm[f], er = eRe[f], ei = eIm[f], xr = fxRe[f], xi = fxIm[f];
            sd[f] = a * sd[f] + b * (dr * dr + di * di);
            se[f] = a * se[f] + b * (er * er + ei * ei);
            sx[f] = a * sx[f] + b * (xr * xr + xi * xi);
            // D * conj(E)
            sdeRe[f] = a * sdeRe[f] + b * (dr * er + di * ei);
            sdeIm[f] = a * sdeIm[f] + b * (di * er - dr * ei);
            // X * conj(D)
            sxdRe[f] = a * sxdRe[f] + b * (xr * dr + xi * di);
            sxdIm[f] = a * sxdIm[f] + b * (xi * dr - xr * di);
            if (f >= NLP_LO && f < NLP_HI) {
                deSum += cohDe(f);
                xdSum += cohXd(f);
                sxBand += sx[f];
            }
        }
        int bandBins = NLP_HI - NLP_LO;
        float deAvg = (float) (deSum / bandBins);
        float xdAvg = 1f - (float) (xdSum / bandBins);
        boolean farPresent = farActive || sxBand > bandBins * ACTIVE_ENERGY * 0.5f;
        if (deAvg > 0.98f && xdAvg > 0.9f) nearState = true;
        else if (deAvg < 0.95f || xdAvg < 0.8f) nearState = false;
        boolean suppress = farPresent && !nearState;
        if (suppress) statSuppressedBlocks++;

        for (int f = 0; f < BINS; f++) {
            float h;
            if (!farPresent) {
                h = 1f;
            } else {
                float cde = cohDe(f);
                h = nearState ? cde : Math.min(cde, 1f - cohXd(f));
                if (!nearState && h < 1f) {
                    if (overDrive >= 4f) { h *= h; h *= h; }
                    else if (overDrive >= 2f) h *= h;
                }
                if (h < gainFloor) h = gainFloor;
                if (h > 1f) h = 1f;
            }
            float g = h < gainSm[f] ? h : 0.5f * gainSm[f] + 0.5f * h;
            gainSm[f] = g;
            eRe[f] *= g;
            eIm[f] *= g;
        }
        irfft(eRe, eIm, tmp);
        for (int i = 0; i < N; i++) pushOut(ola[i] + tmp[i] * window[i]);
        for (int i = 0; i < N; i++) ola[i] = tmp[N + i] * window[N + i];
    }

    private float cohDe(int f) {
        float num = sdeRe[f] * sdeRe[f] + sdeIm[f] * sdeIm[f];
        float c = num / (sd[f] * se[f] + 1e-10f);
        return c > 1f ? 1f : c;
    }

    private float cohXd(int f) {
        float num = sxdRe[f] * sxdRe[f] + sxdIm[f] * sxdIm[f];
        float c = num / (sx[f] * sd[f] + 1e-10f);
        return c > 1f ? 1f : c;
    }

    private void updatePeakPartition() {
        double best = -1;
        int bestK = 0;
        for (int k = 0; k < partitions; k++) {
            double s = 0;
            for (int f = 0; f < BINS; f++) s += fgRe[k][f] * fgRe[k][f] + fgIm[k][f] * fgIm[k][f];
            if (s > best) {
                best = s;
                bestK = k;
            }
        }
        peakPartition = bestK;
    }

    private void resetFilter() {
        for (int k = 0; k < partitions; k++) {
            Arrays.fill(wRe[k], 0f);
            Arrays.fill(wIm[k], 0f);
            Arrays.fill(fgRe[k], 0f);
            Arrays.fill(fgIm[k], 0f);
        }
        bgErrSm = fgErrSm = 0;
        divergeStreak = 0;
        filterResets++;
    }

    // =====================================================================
    // Coarse delay estimation (binary spectrum matching)
    // =====================================================================

    private void estimateDelay(long j0, boolean nearActive) {
        // Far binary spectrum of the reference at the microphone's own timeline position (lag 0).
        readRef(j0 - N, frame, 0, M);
        double farE = 0;
        for (int i = N; i < M; i++) farE += frame[i] * frame[i];
        for (int i = 0; i < M; i++) frame[i] *= window[i];
        rfft(frame, fxRe, fxIm);
        boolean farActive = farE > ACTIVE_ENERGY;
        int farBits = binarySpectrum(fxRe, fxIm, farMean, farActive);
        farHistHead = (farHistHead + 1) % MAX_LAG_BLOCKS;
        farHist[farHistHead] = farBits;
        farActiveHist[farHistHead] = farActive;
        if (!nearActive) return;
        int nearBits = binarySpectrum(dRe, dIm, nearMean, true);
        int counted = 0;
        float costSum = 0f, best = Float.MAX_VALUE;
        int bestLag = -1;
        for (int lag = 0; lag < MAX_LAG_BLOCKS; lag++) {
            int slot = (farHistHead - lag + MAX_LAG_BLOCKS) % MAX_LAG_BLOCKS;
            if (farActiveHist[slot]) {
                int dist = Integer.bitCount(nearBits ^ farHist[slot]);
                lagCost[lag] += (dist - lagCost[lag]) * (1f / 48f);
                if (lagCount[lag] < 1000000) lagCount[lag]++;
            }
            if (lagCount[lag] >= 60) {
                counted++;
                costSum += lagCost[lag];
                if (lagCost[lag] < best) {
                    best = lagCost[lag];
                    bestLag = lag;
                }
            }
        }
        if (bestLag < 0 || counted < MAX_LAG_BLOCKS / 2) return;
        float mean = costSum / counted;
        if (mean - best < 2.5f) return;
        if (candidateLag >= 0 && Math.abs(bestLag - candidateLag) <= 1) candidateStreak++;
        else {
            candidateLag = bestLag;
            candidateStreak = 1;
        }
        if (candidateStreak < (estimatedLag < 0 ? 40 : 150)) return;
        estimatedLag = candidateLag;
        int desiredBulk = Math.max(0, estimatedLag - PRE_MARGIN_BLOCKS);
        if (Math.abs(desiredBulk - bulkBlocks) >= PRE_MARGIN_BLOCKS) {
            shiftBulk(desiredBulk);
        }
        learnedBulkBlocks = bulkBlocks;
    }

    private int binarySpectrum(float[] re, float[] im, float[] mean, boolean adapt) {
        int bits = 0;
        for (int band = 0; band < DELAY_BANDS; band++) {
            int f = DELAY_FIRST_BIN + band * 2;
            float p = re[f] * re[f] + im[f] * im[f] + re[f + 1] * re[f + 1] + im[f + 1] * im[f + 1];
            float lp = (float) Math.log(p + 1f);
            if (adapt) mean[band] += (lp - mean[band]) * (1f / 64f);
            if (lp > mean[band]) bits |= 1 << band;
        }
        return bits;
    }

    private void shiftBulk(int newBulk) {
        int delta = newBulk - bulkBlocks;
        if (Math.abs(delta) >= partitions) {
            resetFilter();
        } else {
            shiftPartitions(wRe, wIm, delta);
            shiftPartitions(fgRe, fgIm, delta);
        }
        bulkBlocks = newBulk;
        rebuildHistory = true;
        delayChanges++;
        updatePeakPartition();
    }

    /** Partition k of the new filter takes old partition k+delta (echo moves toward lower k when bulk grows). */
    private void shiftPartitions(float[][] re, float[][] im, int delta) {
        int K = partitions;
        if (delta > 0) {
            for (int k = 0; k < K; k++) movePartition(re, im, k + delta, k);
        } else if (delta < 0) {
            for (int k = K - 1; k >= 0; k--) movePartition(re, im, k + delta, k);
        }
    }

    private void movePartition(float[][] re, float[][] im, int src, int dst) {
        if (src >= 0 && src < partitions) {
            System.arraycopy(re[src], 0, re[dst], 0, BINS);
            System.arraycopy(im[src], 0, im[dst], 0, BINS);
        } else {
            Arrays.fill(re[dst], 0f);
            Arrays.fill(im[dst], 0f);
        }
    }

    // =====================================================================
    // Diagnostics
    // =====================================================================

    /** Key/value pairs for MdMain.logFields; resets the interval ERLE accumulators. */
    Object[] statsAndReset() {
        if (statD > 0 && statE > 0) erleDb = 10 * Math.log10(statD / statE);
        long rc, rr;
        synchronized (refLock) {
            rc = refChunks;
            rr = refResyncs;
        }
        List<Object> f = new ArrayList<Object>();
        add(f, "bulk_delay_ms", bulkBlocks * N / SAMPLES_PER_MS);
        add(f, "estimated_delay_ms", estimatedLag < 0 ? -1 : estimatedLag * N / SAMPLES_PER_MS);
        add(f, "manual_delay", manualBulkBlocks >= 0);
        add(f, "peak_partition_ms", peakPartition * N / SAMPLES_PER_MS);
        add(f, "erle_db", Double.isNaN(erleDb) ? null : Math.round(erleDb * 10) / 10.0);
        add(f, "far_active_pct", statBlocks == 0 ? 0 : statFarBlocks * 100 / statBlocks);
        add(f, "suppressed_pct", statBlocks == 0 ? 0 : statSuppressedBlocks * 100 / statBlocks);
        add(f, "bypass_pct", statBlocks == 0 ? 0 : statOutD * 100 / statBlocks);
        add(f, "near_state", nearState);
        add(f, "cpu_us_per_block", statBlocks == 0 ? 0 : statCpuNs / statBlocks / 1000);
        add(f, "blocks", blocks);
        add(f, "ref_chunks", rc);
        add(f, "ref_resyncs", rr);
        add(f, "mic_resyncs", micResyncs);
        add(f, "filter_resets", filterResets);
        add(f, "fg_copies", fgCopies);
        add(f, "bg_restores", bgRestores);
        add(f, "delay_changes", delayChanges);
        add(f, "nlp_level", nlpLevel);
        statD = statE = 0;
        statFarBlocks = statBlocks = statSuppressedBlocks = statCpuNs = statOutD = 0;
        return f.toArray();
    }

    private static void add(List<Object> f, String k, Object v) {
        f.add(k);
        f.add(v);
    }

    private void openDump(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            dumpMic = new BufferedOutputStream(new FileOutputStream(new File(dir, "mic_raw.pcm")), 1 << 16);
            dumpRef = new BufferedOutputStream(new FileOutputStream(new File(dir, "ref_aligned.pcm")), 1 << 16);
            dumpOut = new BufferedOutputStream(new FileOutputStream(new File(dir, "aec_out.pcm")), 1 << 16);
        } catch (IOException e) {
            closeDump();
        }
    }

    private final byte[] dumpBuf = new byte[2 * N];

    private void writeDump(long r0) {
        if (dumpMic == null) return;
        if (dumpBytes >= DUMP_CAP_BYTES) {
            closeDump();
            return;
        }
        try {
            writePcm(dumpMic, d);
            readRef(r0 - (long) peakPartition * N, tmp, 0, N);
            writePcm(dumpRef, tmp);
            // Output of this block is the last N samples pushed.
            for (int i = 0; i < N; i++) {
                short s = outFifo[Math.max(0, outLen - N) + i];
                dumpBuf[2 * i] = (byte) s;
                dumpBuf[2 * i + 1] = (byte) (s >> 8);
            }
            dumpOut.write(dumpBuf);
            dumpBytes += 2 * N;
        } catch (IOException e) {
            closeDump();
        }
    }

    private void writePcm(OutputStream out, float[] src) throws IOException {
        for (int i = 0; i < N; i++) {
            int s = Math.round(src[i]);
            s = s > 32767 ? 32767 : s < -32768 ? -32768 : s;
            dumpBuf[2 * i] = (byte) s;
            dumpBuf[2 * i + 1] = (byte) (s >> 8);
        }
        out.write(dumpBuf);
    }

    private void closeDump() {
        OutputStream[] all = {dumpMic, dumpRef, dumpOut};
        dumpMic = dumpRef = dumpOut = null;
        for (OutputStream o : all) {
            if (o != null) try { o.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() {
        synchronized (refLock) {
            closed = true;
        }
        closeDump();
    }

    // =====================================================================
    // FFT helpers
    // =====================================================================

    private void rfft(float[] in, float[] outRe, float[] outIm) {
        System.arraycopy(in, 0, fre, 0, M);
        Arrays.fill(fim, 0f);
        fft.transform(fre, fim, false);
        System.arraycopy(fre, 0, outRe, 0, BINS);
        System.arraycopy(fim, 0, outIm, 0, BINS);
    }

    private void irfft(float[] re, float[] im, float[] out) {
        for (int k = 0; k < BINS; k++) {
            fre[k] = re[k];
            fim[k] = im[k];
        }
        fim[0] = 0f;
        fim[N] = 0f;
        for (int k = 1; k < N; k++) {
            fre[M - k] = re[k];
            fim[M - k] = -im[k];
        }
        fft.transform(fre, fim, true);
        System.arraycopy(fre, 0, out, 0, M);
    }

    static final class Fft {
        final int n;
        final float[] cos, sin;
        final int[] rev;

        Fft(int n) {
            this.n = n;
            cos = new float[n / 2];
            sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                double a = -2 * Math.PI * i / n;
                cos[i] = (float) Math.cos(a);
                sin[i] = (float) Math.sin(a);
            }
            rev = new int[n];
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }

        void transform(float[] re, float[] im, boolean inverse) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (j > i) {
                    float t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1, step = n / size;
                for (int i = 0; i < n; i += size) {
                    for (int j = 0; j < half; j++) {
                        float wr = cos[j * step], wi = inverse ? -sin[j * step] : sin[j * step];
                        int a = i + j, b = a + half;
                        float tr = re[b] * wr - im[b] * wi;
                        float ti = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
            if (inverse) {
                float s = 1f / n;
                for (int i = 0; i < n; i++) {
                    re[i] *= s;
                    im[i] *= s;
                }
            }
        }
    }
}
