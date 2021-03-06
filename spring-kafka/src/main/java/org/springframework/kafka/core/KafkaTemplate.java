/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.core;

import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;

import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.converter.MessageConverter;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;


/**
 * A template for executing high-level operations.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Marius Bogoevici
 * @author Gary Russell
 * @author Igor Stepanov
 * @author Artem Bilan
 * @author Biju Kunjummen
 */
public class KafkaTemplate<K, V> implements KafkaOperations<K, V> {

	protected final Log logger = LogFactory.getLog(this.getClass()); //NOSONAR

	private final ProducerFactory<K, V> producerFactory;

	private final boolean autoFlush;

	private final boolean transactional;

	private final ThreadLocal<Producer<K, V>> producers = new ThreadLocal<>();

	private RecordMessageConverter messageConverter = new MessagingMessageConverter();

	private volatile String defaultTopic;

	private volatile ProducerListener<K, V> producerListener = new LoggingProducerListener<K, V>();


	/**
	 * Create an instance using the supplied producer factory and autoFlush false.
	 * @param producerFactory the producer factory.
	 */
	public KafkaTemplate(ProducerFactory<K, V> producerFactory) {
		this(producerFactory, false);
	}

	/**
	 * Create an instance using the supplied producer factory and autoFlush setting.
	 * Set autoFlush to true if you wish to synchronously interact with Kafka, calling
	 * {@link java.util.concurrent.Future#get()} on the result.
	 * @param producerFactory the producer factory.
	 * @param autoFlush true to flush after each send.
	 */
	public KafkaTemplate(ProducerFactory<K, V> producerFactory, boolean autoFlush) {
		this.producerFactory = producerFactory;
		this.autoFlush = autoFlush;
		this.transactional = producerFactory.transactionCapable();
	}

	/**
	 * The default topic for send methods where a topic is not
	 * providing.
	 * @return the topic.
	 */
	public String getDefaultTopic() {
		return this.defaultTopic;
	}

	/**
	 * Set the default topic for send methods where a topic is not
	 * providing.
	 * @param defaultTopic the topic.
	 */
	public void setDefaultTopic(String defaultTopic) {
		this.defaultTopic = defaultTopic;
	}

	/**
	 * Set a {@link ProducerListener} which will be invoked when Kafka acknowledges
	 * a send operation. By default a {@link LoggingProducerListener} is configured
	 * which logs errors only.
	 * @param producerListener the listener; may be {@code null}.
	 */
	public void setProducerListener(ProducerListener<K, V> producerListener) {
		this.producerListener = producerListener;
	}

	/**
	 * Return the message converter.
	 * @return the message converter.
	 */
	public MessageConverter getMessageConverter() {
		return this.messageConverter;
	}

	/**
	 * Set the message converter to use.
	 * @param messageConverter the message converter.
	 */
	public void setMessageConverter(RecordMessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(V data) {
		return send(this.defaultTopic, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(K key, V data) {
		return send(this.defaultTopic, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(Integer partition, K key, V data) {
		return send(this.defaultTopic, partition, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(Integer partition, Long timestamp, K key, V data) {
		return send(this.defaultTopic, partition, timestamp, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, K key, V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, key, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, Integer partition, K key, V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, partition, key, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, Integer partition, Long timestamp, K key, V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, partition, timestamp, key, data);
		return doSend(producerRecord);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ListenableFuture<SendResult<K, V>> send(Message<?> message) {
		ProducerRecord<?, ?> producerRecord = this.messageConverter.fromMessage(message, this.defaultTopic);
		return doSend((ProducerRecord<K, V>) producerRecord);
	}


	@Override
	public List<PartitionInfo> partitionsFor(String topic) {
		Producer<K, V> producer = getTheProducer();
		try {
			return producer.partitionsFor(topic);
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public Map<MetricName, ? extends Metric> metrics() {
		Producer<K, V> producer = getTheProducer();
		try {
			return producer.metrics();
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public <T> T execute(ProducerCallback<K, V, T> callback) {
		Producer<K, V> producer = getTheProducer();
		try {
			return callback.doInKafka(producer);
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public <T> T executeInTransaction(OperationsCallback<K, V, T> callback) {
		Assert.state(this.transactional, "Producer factory does not support transactions");
		Producer<K, V> producer = this.producers.get();
		Assert.state(producer == null, "Nested calls to 'executeInTransaction' are not allowed");
		producer = this.producerFactory.createProducer();
		this.producers.set(producer);
		producer.beginTransaction();
		T result = null;
		try {
			result = callback.doInOperations(this);
		}
		catch (Exception e) {
			producer.abortTransaction();
			this.producers.remove();
			closeProducer(producer, false);
			producer = null;
		}
		if (producer != null) {
			try {
				producer.commitTransaction();
			}
			finally {
				closeProducer(producer, false);
				this.producers.remove();
			}
		}
		return result;
	}

	/**
	 * {@inheritDoc}
	 * <p><b>Note</b> It only makes sense to invoke this method if the
	 * {@link ProducerFactory} serves up a singleton producer (such as the
	 * {@link DefaultKafkaProducerFactory}).
	 */
	@Override
	public void flush() {
		Producer<K, V> producer = getTheProducer();
		try {
			producer.flush();
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	protected void closeProducer(Producer<K, V> producer, boolean inLocalTx) {
		if (!inLocalTx) {
			producer.close();
		}
	}

	/**
	 * Send the producer record.
	 * @param producerRecord the producer record.
	 * @return a Future for the {@link RecordMetadata}.
	 */
	protected ListenableFuture<SendResult<K, V>> doSend(final ProducerRecord<K, V> producerRecord) {
		final Producer<K, V> producer = getTheProducer();
		if (this.logger.isTraceEnabled()) {
			this.logger.trace("Sending: " + producerRecord);
		}
		final SettableListenableFuture<SendResult<K, V>> future = new SettableListenableFuture<>();
		final boolean inLocalTx = inTransaction();
		producer.send(producerRecord, new Callback() {

			@Override
			public void onCompletion(RecordMetadata metadata, Exception exception) {
				try {
					if (exception == null) {
						future.set(new SendResult<>(producerRecord, metadata));
						if (KafkaTemplate.this.producerListener != null
								&& KafkaTemplate.this.producerListener.isInterestedInSuccess()) {
							KafkaTemplate.this.producerListener.onSuccess(producerRecord.topic(),
									producerRecord.partition(), producerRecord.key(), producerRecord.value(), metadata);
						}
					}
					else {
						future.setException(new KafkaProducerException(producerRecord, "Failed to send", exception));
						if (KafkaTemplate.this.producerListener != null) {
							KafkaTemplate.this.producerListener.onError(producerRecord.topic(),
									producerRecord.partition(),
									producerRecord.key(),
									producerRecord.value(),
									exception);
						}
					}
				}
				finally {
					if (KafkaTemplate.this.producers.get() == null) {
						closeProducer(producer, inLocalTx);
					}
				}
			}

		});
		if (this.autoFlush) {
			flush();
		}
		if (this.logger.isTraceEnabled()) {
			this.logger.trace("Sent: " + producerRecord);
		}
		return future;
	}

	protected boolean inTransaction() {
		return this.producers.get() != null || TransactionSynchronizationManager.isActualTransactionActive();
	}

	private Producer<K, V> getTheProducer() {
		if (this.transactional) {
			Producer<K, V> producer = this.producers.get();
			if (producer != null) {
				return producer;
			}
			KafkaResourceHolder<K, V> holder = ProducerFactoryUtils
					.getTransactionalResourceHolder(this.producerFactory);
			return holder.getProducer();
		}
		else {
			return this.producerFactory.createProducer();
		}
	}

}
