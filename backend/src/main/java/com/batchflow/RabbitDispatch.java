package com.batchflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name="app.queue-mode",havingValue="rabbitmq")
public class RabbitDispatch {
    public static final String EXCHANGE="batchflow.tasks";
    public static final String QUEUE="batchflow.imports";
    @Bean DirectExchange taskExchange() { return new DirectExchange(EXCHANGE,true,false); }
    @Bean Queue taskQueue() { return QueueBuilder.durable(QUEUE).build(); }
    @Bean Binding taskBinding(Queue taskQueue,DirectExchange taskExchange) { return BindingBuilder.bind(taskQueue).to(taskExchange).with("import"); }

    @Bean
    @ConditionalOnProperty(name="app.role",havingValue="api")
    Publisher taskPublisher(JdbcTemplate jdbc,TransactionTemplate tx,RabbitTemplate rabbit) { return new Publisher(jdbc,tx,rabbit); }
    @Bean
    @ConditionalOnProperty(name="app.role",havingValue="worker")
    Listener taskListener(WorkerRunner worker) { return new Listener(worker); }

    public static class Publisher {
        private static final Logger log=LoggerFactory.getLogger(Publisher.class);
        private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final RabbitTemplate rabbit;
        public Publisher(JdbcTemplate jdbc,TransactionTemplate tx,RabbitTemplate rabbit) { this.jdbc=jdbc;this.tx=tx;this.rabbit=rabbit; }
        @Scheduled(initialDelay=1000,fixedDelay=1000)
        public void publish() {
            for (int i=0;i<20;i++) {
                Boolean found=tx.execute(s -> {
                    var rows=jdbc.queryForList("SELECT * FROM task_outbox WHERE published_at IS NULL AND next_attempt_at<=clock_timestamp() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
                    if (rows.isEmpty()) return false;
                    var row=rows.getFirst(); long id=((Number)row.get("id")).longValue();
                    try {
                        CorrelationData cd=new CorrelationData("outbox-"+id);
                        // Spring AMQP correlated confirms: a broker ACK and no mandatory return are both required.
                        rabbit.convertAndSend(EXCHANGE,"import",row.get("task_id").toString(),cd);
                        var confirm=cd.getFuture().get(5,TimeUnit.SECONDS);
                        if (!confirm.isAck() || cd.getReturned()!=null) throw new IllegalStateException("消息未被可靠路由到队列");
                        jdbc.update("UPDATE task_outbox SET published_at=now(),last_error=NULL WHERE id=?",id);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        jdbc.update("UPDATE task_outbox SET attempts=attempts+1,next_attempt_at=clock_timestamp()+interval '5 seconds',last_error=? WHERE id=?",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()),id);
                        log.warn("Outbox {} 投递未确认，将重试",id);
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(found)) break;
            }
        }
    }
    public static class Listener {
        private final WorkerRunner worker;
        public Listener(WorkerRunner worker) { this.worker=worker; }
        @RabbitListener(queues=QUEUE)
        public void receive(String taskId) { worker.receive(taskId); }
    }
}
