/*
 * Copyright 2015-2018 the original author or authors.
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

package org.springframework.cloud.stream.app.sftp.source;

import java.util.Collections;
import java.util.function.Consumer;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.app.file.FileConsumerProperties;
import org.springframework.cloud.stream.app.file.FileUtils;
import org.springframework.cloud.stream.app.file.remote.RemoteFileDeletingTransactionSynchronizationProcessor;
import org.springframework.cloud.stream.app.trigger.TriggerConfiguration;
import org.springframework.cloud.stream.app.trigger.TriggerPropertiesMaxMessagesDefaultUnlimited;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowBuilder;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.SourcePollingChannelAdapterSpec;
import org.springframework.integration.file.filters.ChainFileListFilter;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.metadata.SimpleMetadataStore;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.dsl.SftpInboundChannelAdapterSpec;
import org.springframework.integration.sftp.dsl.SftpStreamingInboundChannelAdapterSpec;
import org.springframework.integration.sftp.filters.SftpPersistentAcceptOnceFileListFilter;
import org.springframework.integration.sftp.filters.SftpRegexPatternFileListFilter;
import org.springframework.integration.sftp.filters.SftpSimplePatternFileListFilter;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.integration.transaction.DefaultTransactionSynchronizationFactory;
import org.springframework.integration.transaction.PseudoTransactionManager;
import org.springframework.integration.transaction.TransactionSynchronizationProcessor;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.util.StringUtils;

import com.jcraft.jsch.ChannelSftp.LsEntry;

/**
 * @author Gary Russell
 * @author Artem Bilan
 */
@EnableBinding(Source.class)
@EnableConfigurationProperties({ SftpSourceProperties.class, FileConsumerProperties.class })
@Import({ TriggerConfiguration.class,
		SftpSourceSessionFactoryConfiguration.class,
		TriggerPropertiesMaxMessagesDefaultUnlimited.class })
public class SftpSourceConfiguration {

	@Autowired
	@Qualifier("defaultPoller")
	private PollerMetadata defaultPoller;

	@Autowired
	private Source source;

	@Autowired(required = false)
	private SftpRemoteFileTemplate sftpTemplate;

	@Bean
	public IntegrationFlow sftpInboundFlow(SessionFactory<LsEntry> sftpSessionFactory, SftpSourceProperties properties,
			FileConsumerProperties fileConsumerProperties) {

		ChainFileListFilter<LsEntry> filterChain = new ChainFileListFilter<>();
		if (StringUtils.hasText(properties.getFilenamePattern())) {
			filterChain.addFilter(new SftpSimplePatternFileListFilter(properties.getFilenamePattern()));
		}
		else if (properties.getFilenameRegex() != null) {
			filterChain.addFilter(new SftpRegexPatternFileListFilter(properties.getFilenameRegex()));
		}
		filterChain.addFilter(new SftpPersistentAcceptOnceFileListFilter(new SimpleMetadataStore(), "sftpSource"));

		IntegrationFlowBuilder flowBuilder;
		if (!properties.isStream()) {
			SftpInboundChannelAdapterSpec messageSourceBuilder =
					Sftp.inboundAdapter(sftpSessionFactory)
							.preserveTimestamp(properties.isPreserveTimestamp())
							.remoteDirectory(properties.getRemoteDir())
							.remoteFileSeparator(properties.getRemoteFileSeparator())
							.localDirectory(properties.getLocalDir())
							.autoCreateLocalDirectory(properties.isAutoCreateLocalDir())
							.temporaryFileSuffix(properties.getTmpFileSuffix())
							.deleteRemoteFiles(properties.isDeleteRemoteFiles());

			messageSourceBuilder.filter(filterChain);

			flowBuilder = FileUtils.enhanceFlowForReadingMode(
					IntegrationFlows.from(messageSourceBuilder, consumerSpec()), fileConsumerProperties);
		}
		else {
			SftpStreamingInboundChannelAdapterSpec messageSourceStreamingSpec =
					Sftp.inboundStreamingAdapter(this.sftpTemplate)
							.remoteDirectory(properties.getRemoteDir())
							.remoteFileSeparator(properties.getRemoteFileSeparator())
							.filter(filterChain);

			flowBuilder = FileUtils.enhanceStreamFlowForReadingMode(
					IntegrationFlows.from(messageSourceStreamingSpec,
							properties.isDeleteRemoteFiles() ? consumerSpecWithDelete(properties) : consumerSpec()),
					fileConsumerProperties);
		}

		return flowBuilder
				.channel(this.source.output())
				.get();
	}

	private Consumer<SourcePollingChannelAdapterSpec> consumerSpec() {
		return spec -> spec.poller(SftpSourceConfiguration.this.defaultPoller);
	}

	private Consumer<SourcePollingChannelAdapterSpec> consumerSpecWithDelete(final SftpSourceProperties properties) {
		final PollerMetadata poller = new PollerMetadata();
		BeanUtils.copyProperties(this.defaultPoller, poller, "transactionSynchronizationFactory");
		TransactionSynchronizationProcessor processor = new RemoteFileDeletingTransactionSynchronizationProcessor(
				this.sftpTemplate, properties.getRemoteFileSeparator());
		poller.setTransactionSynchronizationFactory(new DefaultTransactionSynchronizationFactory(processor));
		poller.setAdviceChain(Collections.singletonList(new TransactionInterceptor(
				new PseudoTransactionManager(), new MatchAlwaysTransactionAttributeSource())));
		return spec -> spec.poller(poller);
	}

	@Bean
	@ConditionalOnProperty(name = "sftp.stream")
	public SftpRemoteFileTemplate sftpTemplate(SessionFactory<LsEntry> sftpSessionFactory) {
		return new SftpRemoteFileTemplate(sftpSessionFactory);
	}

}
