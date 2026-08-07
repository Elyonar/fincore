package org.elyonar.fincore.notification.internal.send;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the drain continuously.
 *
 * <p>A tick, not a batch window. Constitution #9 forbids scheduled processing windows, and a
 * notification queue is the easiest place to accidentally introduce one: "send the morning's
 * alerts at 8am" is a batch window wearing a friendly name, and it is exactly what turns a debit
 * alert into a receipt.
 */
@Component
public class SendScheduler {

    private static final Logger log = LoggerFactory.getLogger(SendScheduler.class);

    private final SendWorker worker;
    private final int batchSize;

    public SendScheduler(
            SendWorker worker, @Value("${fincore.notification.worker.batch-size:50}") int batchSize) {
        this.worker = worker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${fincore.notification.worker.interval-ms:1000}")
    public void tick() {
        try {
            worker.drain(batchSize);
        } catch (RuntimeException e) {
            // Never propagate: a throw out of a scheduled method cancels the schedule in some
            // configurations, which converts a transient database blip into a queue that stops
            // draining and nothing saying so.
            log.error("send drain failed; will retry on the next tick", e);
        }
    }
}
