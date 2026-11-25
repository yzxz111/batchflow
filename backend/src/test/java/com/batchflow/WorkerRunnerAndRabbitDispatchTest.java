package com.batchflow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.batchflow.Models.Lease;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerRunnerAndRabbitDispatchTest {

    @Test
    void receiveWaitsWhenPollIsBusyInsteadOfDroppingTask() throws Exception {
        TaskEngine engine = mock(TaskEngine.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkerRunner runner = new WorkerRunner(engine, jdbc, "rabbitmq");

        CountDownLatch pollEnteredClaim = new CountDownLatch(1);
        CountDownLatch allowPollFinish = new CountDownLatch(1);
        Lease taskLease = new Lease("task-42", "w1", 1L, 0L, 10, "/tmp/a.csv", Map.of());
        AtomicBoolean taskProcessed = new AtomicBoolean(false);

        when(engine.claim(anyString(), isNull(), eq(true))).thenAnswer(inv -> {
            pollEnteredClaim.countDown();
            assertTrue(allowPollFinish.await(5, TimeUnit.SECONDS));
            return null;
        });
        when(engine.claim(anyString(), eq("task-42"), eq(false))).thenReturn(taskLease);
        doAnswer(inv -> {
            taskProcessed.set(true);
            return null;
        }).when(engine).process(taskLease);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> pollFuture = pool.submit(runner::poll);
            assertTrue(pollEnteredClaim.await(5, TimeUnit.SECONDS));

            Future<?> receiveFuture = pool.submit(() -> runner.receive("task-42"));
            Thread.sleep(100);
            assertFalse(receiveFuture.isDone(), "receive() 应等待正在运行的 poll() 释放锁，不能静默返回丢弃消息");

            allowPollFinish.countDown();
            pollFuture.get(5, TimeUnit.SECONDS);
            receiveFuture.get(5, TimeUnit.SECONDS);

            assertTrue(taskProcessed.get(), "释放锁后 receive() 应继续领取并处理 task-42");
            verify(engine).claim(anyString(), eq("task-42"), eq(false));
            verify(engine).process(taskLease);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void receiveRethrowsClaimFailureWhilePollSwallowsIt() {
        TaskEngine engine = mock(TaskEngine.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkerRunner runner = new WorkerRunner(engine, jdbc, "rabbitmq");

        when(engine.claim(anyString(), isNull(), eq(true))).thenThrow(new IllegalStateException("DB transient"));
        assertDoesNotThrow(runner::poll);

        when(engine.claim(anyString(), eq("task-err"), eq(false))).thenThrow(new IllegalStateException("DB transient"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> runner.receive("task-err"));
        assertEquals("DB transient", thrown.getMessage());
    }

    @Test
    void pollUsesDatabaseOrRabbitmqOnlyExpiredModeAccordingly() {
        TaskEngine engine = mock(TaskEngine.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new WorkerRunner(engine, jdbc, "database").poll();
        verify(engine).claim(anyString(), isNull(), eq(false));

        new WorkerRunner(engine, jdbc, "rabbitmq").poll();
        verify(engine).claim(anyString(), isNull(), eq(true));
    }

    @Test
    void rabbitListenerPropagatesReceiveExceptionForBrokerRequeue() {
        TaskEngine engine = mock(TaskEngine.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WorkerRunner runner = new WorkerRunner(engine, jdbc, "rabbitmq");
        RabbitDispatch.Listener listener = new RabbitDispatch.Listener(runner);

        when(engine.claim(anyString(), eq("task-nack"), eq(false))).thenThrow(new RuntimeException("claim failed"));
        assertThrows(RuntimeException.class, () -> listener.receive("task-nack"));
    }

    @Test
    void rabbitPublisherMarksPublishedOnAckAndRetriesOnNackOrReturn() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
        };
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        RabbitDispatch.Publisher publisher = new RabbitDispatch.Publisher(jdbc, tx, rabbit);

        // First call returns one outbox row, second call returns empty to break loop.
        when(jdbc.queryForList(contains("FROM task_outbox")))
                .thenReturn(List.of(Map.of("id", 7L, "task_id", "t-1")))
                .thenReturn(List.of());

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(RabbitDispatch.EXCHANGE), eq("import"), eq("t-1"), any(CorrelationData.class));

        publisher.publish();
        verify(jdbc).update(contains("SET published_at=now()"), eq(7L));

        // Now simulate returned unroutable message -> must record retry instead of marking published.
        reset(jdbc, rabbit);
        when(jdbc.queryForList(contains("FROM task_outbox")))
                .thenReturn(List.of(Map.of("id", 8L, "task_id", "t-2")))
                .thenReturn(List.of());

        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.setReturned(mock(ReturnedMessage.class));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(RabbitDispatch.EXCHANGE), eq("import"), eq("t-2"), any(CorrelationData.class));

        publisher.publish();
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("SET attempts=attempts+1"), errCaptor.capture(), eq(8L));
        assertTrue(errCaptor.getValue().contains("消息未被可靠路由到队列"));
        verify(jdbc, never()).update(contains("SET published_at=now()"), eq(8L));
    }
}
