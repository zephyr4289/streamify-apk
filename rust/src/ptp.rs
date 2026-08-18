use std::sync::atomic::{AtomicI64, Ordering};

pub struct PtpFilter {
    clock_offset_nanos: AtomicI64,
    rtt_nanos: AtomicI64,
    initialized: bool,
    ema_offset: f64,
    ema_rtt: f64,
    alpha: f64,
}

impl PtpFilter {
    pub fn new(alpha: f64) -> Self {
        Self {
            clock_offset_nanos: AtomicI64::new(0),
            rtt_nanos: AtomicI64::new(0),
            initialized: false,
            ema_offset: 0.0,
            ema_rtt: 0.0,
            alpha: alpha.clamp(0.01, 1.0),
        }
    }

    /// Processes IEEE 1588 four timestamps (t0, t1, t2, t3) in nanoseconds.
    /// Offset = ((t1 - t0) + (t2 - t3)) / 2
    /// Delay (RTT) = (t3 - t0) - (t2 - t1)
    pub fn process_timestamps(&mut self, t0: i64, t1: i64, t2: i64, t3: i64) -> i64 {
        let raw_offset = ((t1 - t0) + (t2 - t3)) as f64 / 2.0;
        let raw_rtt = ((t3 - t0) - (t2 - t1)).max(0) as f64;

        if !self.initialized {
            self.ema_offset = raw_offset;
            self.ema_rtt = raw_rtt;
            self.initialized = true;
        } else {
            // Adaptive Kalman-like EMA filtering
            self.ema_offset = self.alpha * raw_offset + (1.0 - self.alpha) * self.ema_offset;
            self.ema_rtt = self.alpha * raw_rtt + (1.0 - self.alpha) * self.ema_rtt;
        }

        let final_offset = self.ema_offset as i64;
        let final_rtt = self.ema_rtt as i64;

        self.clock_offset_nanos.store(final_offset, Ordering::Release);
        self.rtt_nanos.store(final_rtt, Ordering::Release);

        final_offset
    }

    pub fn get_clock_offset_nanos(&self) -> i64 {
        self.clock_offset_nanos.load(Ordering::Acquire)
    }

    pub fn get_rtt_nanos(&self) -> i64 {
        self.rtt_nanos.load(Ordering::Acquire)
    }

    pub fn reset(&mut self) {
        self.clock_offset_nanos.store(0, Ordering::Release);
        self.rtt_nanos.store(0, Ordering::Release);
        self.initialized = false;
        self.ema_offset = 0.0;
        self.ema_rtt = 0.0;
    }
}
