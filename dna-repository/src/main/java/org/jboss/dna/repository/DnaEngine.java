/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.dna.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.jcip.annotations.Immutable;
import org.jboss.dna.common.collection.Problem;
import org.jboss.dna.common.collection.Problems;
import org.jboss.dna.common.collection.SimpleProblems;
import org.jboss.dna.common.util.CheckArg;
import org.jboss.dna.common.util.Logger;
import org.jboss.dna.common.util.NamedThreadFactory;
import org.jboss.dna.graph.ExecutionContext;
import org.jboss.dna.graph.Graph;
import org.jboss.dna.graph.JcrLexicon;
import org.jboss.dna.graph.JcrMixLexicon;
import org.jboss.dna.graph.JcrNtLexicon;
import org.jboss.dna.graph.Location;
import org.jboss.dna.graph.Node;
import org.jboss.dna.graph.Subgraph;
import org.jboss.dna.graph.connector.RepositoryConnectionFactory;
import org.jboss.dna.graph.connector.RepositoryContext;
import org.jboss.dna.graph.connector.RepositorySource;
import org.jboss.dna.graph.connector.RepositorySourceException;
import org.jboss.dna.graph.mimetype.ExtensionBasedMimeTypeDetector;
import org.jboss.dna.graph.mimetype.MimeTypeDetector;
import org.jboss.dna.graph.mimetype.MimeTypeDetectorConfig;
import org.jboss.dna.graph.mimetype.MimeTypeDetectors;
import org.jboss.dna.graph.observe.ObservationBus;
import org.jboss.dna.graph.property.Name;
import org.jboss.dna.graph.property.Path;
import org.jboss.dna.graph.property.PathExpression;
import org.jboss.dna.graph.property.PathNotFoundException;
import org.jboss.dna.graph.property.Property;
import org.jboss.dna.repository.sequencer.SequencerConfig;
import org.jboss.dna.repository.sequencer.SequencingService;

/**
 * A single instance of the DNA services, which is obtained after setting up the {@link DnaConfiguration#build() configuration}.
 * 
 * @see DnaConfiguration
 */
@Immutable
public class DnaEngine {

    public static final String CONFIGURATION_REPOSITORY_NAME = "dna:configuration";

    protected final DnaConfiguration.ConfigurationDefinition configuration;
    private final ConfigurationScanner scanner;
    private final Problems problems;
    protected final ExecutionContext context;

    private final RepositoryService repositoryService;
    private final SequencingService sequencingService;
    private final ExecutorService executorService;
    private final MimeTypeDetectors detectors;

    protected DnaEngine( ExecutionContext context,
                         DnaConfiguration.ConfigurationDefinition configuration ) {
        this.problems = new SimpleProblems();

        // Use the configuration's context ...
        this.detectors = new MimeTypeDetectors();
        this.context = context.with(detectors);

        // And set up the scanner ...
        this.configuration = configuration;
        this.scanner = new ConfigurationScanner(this.problems, this.context, this.configuration);

        // Add the mime type detectors in the configuration ...
        for (MimeTypeDetectorConfig config : scanner.getMimeTypeDetectors()) {
            detectors.addDetector(config);
        }
        // Add an extension-based detector by default ...
        detectors.addDetector(new MimeTypeDetectorConfig("ExtensionDetector", "Extension-based MIME type detector",
                                                         ExtensionBasedMimeTypeDetector.class));

        // Create the RepositoryContext that the configuration repository source should use ...
        ObservationBus configurationChangeBus = new ObservationBus();
        RepositoryContext configContext = new SimpleRepositoryContext(context, configurationChangeBus, null);
        final RepositorySource configSource = this.configuration.getRepositorySource();
        configSource.initialize(configContext);

        // Create the RepositoryService, pointing it to the configuration repository ...
        Path pathToConfigurationRoot = this.configuration.getPath();
        String configWorkspaceName = this.configuration.getWorkspace();
        repositoryService = new RepositoryService(configSource, configWorkspaceName, pathToConfigurationRoot, context, problems);

        // Now register the repository service to be notified of changes to the configuration ...
        configurationChangeBus.register(repositoryService);

        // Create the sequencing service ...
        ThreadFactory threadPoolFactory = new NamedThreadFactory(configuration.getName());
        executorService = Executors.newScheduledThreadPool(10, threadPoolFactory);
        sequencingService = new SequencingService();
        sequencingService.setExecutionContext(context);
        sequencingService.setExecutorService(executorService);
        sequencingService.setRepositoryLibrary(repositoryService.getRepositoryLibrary());
        for (SequencerConfig sequencerConfig : scanner.getSequencingConfigurations()) {
            sequencingService.addSequencer(sequencerConfig);
        }
    }


    /**
     * Get the problems that were encountered when setting up this engine from the configuration.
     * 
     * @return the problems, which may be empty but will never be null
     */
    public Problems getProblems() {
        return problems;
    }

    /**
     * Get the context in which this engine is executing.
     * 
     * @return the execution context; never null
     */
    public final ExecutionContext getExecutionContext() {
        return context;
    }

    /**
     * Get the {@link RepositorySource} instance used by this engine.
     * 
     * @param repositoryName the name of the repository source
     * @return the source, or null if no source with the given name exists
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final RepositorySource getRepositorySource( String repositoryName ) {
        checkRunning();
        return repositoryService.getRepositoryLibrary().getSource(repositoryName);
    }

    /**
     * Get a factory of connections, backed by the RepositorySor
     * 
     * @return the connection factory; never null
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final RepositoryConnectionFactory getRepositoryConnectionFactory() {
        checkRunning();
        return repositoryService.getRepositoryLibrary();
    }

    /**
     * Get the repository service.
     * 
     * @return the repository service owned by this engine; never null
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final RepositoryService getRepositoryService() {
        checkRunning();
        return repositoryService;
    }

    /**
     * Get a graph to the underlying source.
     * 
     * @param sourceName the name of the source
     * @return the graph
     * @throws IllegalArgumentException if the source name is null
     * @throws RepositorySourceException if a source with the supplied name does not exist
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final Graph getGraph( String sourceName ) {
        CheckArg.isNotNull(sourceName, "sourceName");
        return getGraph(getExecutionContext(), sourceName);
    }

    /**
     * Get a graph to the underlying source, using the supplied context. Note that the supplied context should be a derivative of
     * the engine's {@link #getExecutionContext() context}.
     * 
     * @param context the context of execution for this graph; may not be null
     * @param sourceName the name of the source
     * @return the graph
     * @throws IllegalArgumentException if the context or source name are null
     * @throws RepositorySourceException if a source with the supplied name does not exist
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final Graph getGraph( ExecutionContext context,
                                 String sourceName ) {
        CheckArg.isNotNull(context, "context");
        CheckArg.isNotNull(sourceName, "sourceName");
        checkRunning();
        Graph graph = Graph.create(sourceName, getRepositoryService().getRepositoryLibrary(), context);
        if (configuration.getRepositorySource().getName().equals(sourceName) && configuration.getWorkspace() != null) {
            // set the workspace ...
            graph.useWorkspace(configuration.getWorkspace());
        }
        return graph;
    }

    /**
     * Get the sequencing service.
     * 
     * @return the sequencing service owned by this engine; never null
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    public final SequencingService getSequencingService() {
        checkRunning();
        return sequencingService;
    }

    /**
     * Return the component that is able to detect MIME types given the name of a stream and a stream.
     * 
     * @return the MIME type detector used by this engine; never null
     * @throws IllegalStateException if this engine was not {@link #start() started}
     */
    protected final MimeTypeDetector getMimeTypeDetector() {
        checkRunning();
        return detectors;
    }

    protected final boolean checkRunning() {
        if (repositoryService.getAdministrator().isStarted() && sequencingService.getAdministrator().isStarted()) {
            return true;
        }
        throw new IllegalStateException(RepositoryI18n.engineIsNotRunning.text());
    }

    /*
     * Lifecycle methods
     */
    /**
     * Start this engine to make it available for use.
     * 
     * @throws IllegalStateException if this method is called when already shut down.
     * @see #shutdown()
     */
    public void start() {
        if (getProblems().hasErrors()) {
            // First log the messages ...
            Logger log = Logger.getLogger(getClass());
            log.error(RepositoryI18n.errorsPreventStarting);
            for (Problem problem : getProblems()) {
                log.error(problem.getMessage(), problem.getParameters());
            }
            // Then throw an exception ...
            throw new IllegalStateException(RepositoryI18n.errorsPreventStarting.text());
        }
        repositoryService.getAdministrator().start();
        sequencingService.getAdministrator().start();
    }

    /**
     * Shutdown this engine to close all connections, terminate any ongoing background operations (such as sequencing), and
     * reclaim any resources that were acquired by this engine. This method may be called multiple times, but only the first time
     * has an effect.
     * 
     * @see #start()
     */
    public void shutdown() {
        // Then terminate the executor service, which may be running background jobs that are not yet completed
        // and which will prevent new jobs being submitted (to the sequencing service) ...
        executorService.shutdown();

        // First, shutdown the sequencing service, which will prevent any additional jobs from going through ...
        sequencingService.getAdministrator().shutdown();

        // Finally shut down the repository source, which closes all connections ...
        repositoryService.getAdministrator().shutdown();
    }

    /**
     * Blocks until the shutdown has completed, or the timeout occurs, or the current thread is interrupted, whichever happens
     * first.
     * 
     * @param timeout the maximum time to wait for each component in this engine
     * @param unit the time unit of the timeout argument
     * @return <tt>true</tt> if this service complete shut down and <tt>false</tt> if the timeout elapsed before it was shut down
     *         completely
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination( long timeout,
                                     TimeUnit unit ) throws InterruptedException {
        if (!sequencingService.getAdministrator().awaitTermination(timeout, unit)) return false;
        if (!executorService.awaitTermination(timeout, unit)) return false;
        if (!repositoryService.getAdministrator().awaitTermination(timeout, unit)) return false;
        return true;
    }

    /**
     * Get a graph to the configuration content.
     * 
     * @return a graph to the configuration content
     */
    protected Graph getConfigurationGraph() {
        Graph result = Graph.create(configuration.getRepositorySource(), context);
        if (configuration.getWorkspace() != null) {
            result.useWorkspace(configuration.getWorkspace());
        }
        return result;
    }

    /**
     * The component responsible for reading the configuration repository and (eventually) for propagating changes in the
     * configuration repository into the services.
     */
    protected class ConfigurationScanner {
        private final Problems problems;
        private final ExecutionContext context;
        private final DnaConfiguration.ConfigurationDefinition configurationRepository;

        protected ConfigurationScanner( Problems problems,
                                        ExecutionContext context,
                                        DnaConfiguration.ConfigurationDefinition configurationRepository ) {
            this.problems = problems;
            this.context = context;
            this.configurationRepository = configurationRepository;
        }

        public List<MimeTypeDetectorConfig> getMimeTypeDetectors() {
            List<MimeTypeDetectorConfig> detectors = new ArrayList<MimeTypeDetectorConfig>();
            Graph graph = Graph.create(configurationRepository.getRepositorySource(), context);
            Path pathToSequencersNode = context.getValueFactories().getPathFactory().create(configurationRepository.getPath(),
                                                                                            DnaLexicon.MIME_TYPE_DETECTORS);
            try {
                Subgraph subgraph = graph.getSubgraphOfDepth(2).at(pathToSequencersNode);

                Set<Name> skipProperties = new HashSet<Name>();
                skipProperties.add(DnaLexicon.READABLE_NAME);
                skipProperties.add(DnaLexicon.DESCRIPTION);
                skipProperties.add(DnaLexicon.CLASSNAME);
                skipProperties.add(DnaLexicon.CLASSPATH);
                skipProperties.add(DnaLexicon.PATH_EXPRESSION);
                Set<String> skipNamespaces = new HashSet<String>();
                skipNamespaces.add(JcrLexicon.Namespace.URI);
                skipNamespaces.add(JcrNtLexicon.Namespace.URI);
                skipNamespaces.add(JcrMixLexicon.Namespace.URI);

                for (Location detectorLocation : subgraph.getRoot().getChildren()) {
                    Node node = subgraph.getNode(detectorLocation);
                    String name = stringValueOf(node, DnaLexicon.READABLE_NAME);
                    if (name == null) name = stringValueOf(node);
                    String desc = stringValueOf(node, DnaLexicon.DESCRIPTION);
                    String classname = stringValueOf(node, DnaLexicon.CLASSNAME);
                    String[] classpath = stringValuesOf(node, DnaLexicon.CLASSPATH);
                    Map<String, Object> properties = new HashMap<String, Object>();
                    for (Property property : node.getProperties()) {
                        Name propertyName = property.getName();
                        if (skipNamespaces.contains(propertyName.getNamespaceUri())) continue;
                        if (skipProperties.contains(propertyName)) continue;
                        if (property.isSingle()) {
                            properties.put(propertyName.getLocalName(), property.getFirstValue());
                        } else {
                            properties.put(propertyName.getLocalName(), property.getValuesAsArray());
                        }
                    }
                    MimeTypeDetectorConfig config = new MimeTypeDetectorConfig(name, desc, properties, classname, classpath);
                    detectors.add(config);
                }
            } catch (PathNotFoundException e) {
                // no detectors registered ...
            }
            return detectors;
        }

        public List<SequencerConfig> getSequencingConfigurations() {
            List<SequencerConfig> configs = new ArrayList<SequencerConfig>();
            Graph graph = Graph.create(configurationRepository.getRepositorySource(), context);
            Path pathToSequencersNode = context.getValueFactories().getPathFactory().create(configurationRepository.getPath(),
                                                                                            DnaLexicon.SEQUENCERS);
            try {
                Subgraph subgraph = graph.getSubgraphOfDepth(2).at(pathToSequencersNode);

                Set<Name> skipProperties = new HashSet<Name>();
                skipProperties.add(DnaLexicon.READABLE_NAME);
                skipProperties.add(DnaLexicon.DESCRIPTION);
                skipProperties.add(DnaLexicon.CLASSNAME);
                skipProperties.add(DnaLexicon.CLASSPATH);
                skipProperties.add(DnaLexicon.PATH_EXPRESSION);
                Set<String> skipNamespaces = new HashSet<String>();
                skipNamespaces.add(JcrLexicon.Namespace.URI);
                skipNamespaces.add(JcrNtLexicon.Namespace.URI);
                skipNamespaces.add(JcrMixLexicon.Namespace.URI);

                for (Location sequencerLocation : subgraph.getRoot().getChildren()) {
                    Node sequencerNode = subgraph.getNode(sequencerLocation);
                    String name = stringValueOf(sequencerNode, DnaLexicon.READABLE_NAME);
                    if (name == null) name = stringValueOf(sequencerNode);
                    String desc = stringValueOf(sequencerNode, DnaLexicon.DESCRIPTION);
                    String classname = stringValueOf(sequencerNode, DnaLexicon.CLASSNAME);
                    String[] classpath = stringValuesOf(sequencerNode, DnaLexicon.CLASSPATH);
                    String[] expressionStrings = stringValuesOf(sequencerNode, DnaLexicon.PATH_EXPRESSION);
                    List<PathExpression> pathExpressions = new ArrayList<PathExpression>();
                    if (expressionStrings != null) {
                        for (String expressionString : expressionStrings) {
                            try {
                                pathExpressions.add(PathExpression.compile(expressionString));
                            } catch (Throwable t) {
                                problems.addError(t,
                                                  RepositoryI18n.pathExpressionIsInvalidOnSequencer,
                                                  expressionString,
                                                  name,
                                                  t.getLocalizedMessage());
                            }
                        }
                    }
                    String[] goodExpressionStrings = new String[pathExpressions.size()];
                    for (int i = 0; i != pathExpressions.size(); ++i) {
                        PathExpression expression = pathExpressions.get(i);
                        goodExpressionStrings[i] = expression.getExpression();
                    }
                    Map<String, Object> properties = new HashMap<String, Object>();
                    for (Property property : sequencerNode.getProperties()) {
                        Name propertyName = property.getName();
                        if (skipNamespaces.contains(propertyName.getNamespaceUri())) continue;
                        if (skipProperties.contains(propertyName)) continue;
                        if (property.isSingle()) {
                            properties.put(propertyName.getLocalName(), property.getFirstValue());
                        } else {
                            properties.put(propertyName.getLocalName(), property.getValuesAsArray());
                        }
                    }
                    SequencerConfig config = new SequencerConfig(name, desc, properties, classname, classpath,
                                                                 goodExpressionStrings);
                    configs.add(config);
                }
            } catch (PathNotFoundException e) {
                // no detectors registered ...
            }
            return configs;
        }

        private String stringValueOf( Node node ) {
            return node.getLocation().getPath().getLastSegment().getString(context.getNamespaceRegistry());
        }

        private String stringValueOf( Node node,
                                      Name propertyName ) {
            Property property = node.getProperty(propertyName);
            if (property == null) return null;
            if (property.isEmpty()) return null;
            return context.getValueFactories().getStringFactory().create(property.getFirstValue());
        }

        private String[] stringValuesOf( Node node,
                                         Name propertyName ) {
            Property property = node.getProperty(propertyName);
            if (property == null) return null;
            return context.getValueFactories().getStringFactory().create(property.getValuesAsArray());
        }

    }
}
