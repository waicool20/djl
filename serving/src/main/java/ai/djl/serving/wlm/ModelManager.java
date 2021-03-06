/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.serving.wlm;

import ai.djl.ModelException;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.serving.http.BadRequestException;
import ai.djl.serving.http.DescribeModelResponse;
import ai.djl.serving.http.StatusResponse;
import ai.djl.serving.util.ConfigManager;
import ai.djl.serving.util.NettyUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A class that in charge of managing models. */
public final class ModelManager {

    private static final Logger logger = LoggerFactory.getLogger(ModelManager.class);

    private static ModelManager modelManager;

    private ConfigManager configManager;
    private WorkLoadManager wlm;
    private ConcurrentHashMap<String, ModelInfo> models;
    private Set<String> startupModels;

    private ModelManager(ConfigManager configManager) {
        this.configManager = configManager;
        wlm = new WorkLoadManager(configManager);
        models = new ConcurrentHashMap<>();
        startupModels = new HashSet<>();
    }

    /**
     * Initialized the global {@code ModelManager} instance.
     *
     * @param configManager the configuration
     */
    public static void init(ConfigManager configManager) {
        modelManager = new ModelManager(configManager);
    }

    /**
     * Returns the singleton {@code ModelManager} instance.
     *
     * @return the singleton {@code ModelManager} instance
     */
    public static ModelManager getInstance() {
        return modelManager;
    }

    /**
     * Registers and loads a model.
     *
     * @param modelName the name of the model for HTTP endpoint
     * @param modelUrl the model url
     * @param batchSize the batch size
     * @param maxBatchDelay the maximum delay for batching
     * @return a {@code CompletableFuture} instance
     */
    public CompletableFuture<ModelInfo> registerModel(
            final String modelName,
            final String modelUrl,
            final int batchSize,
            final int maxBatchDelay) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        Criteria<Input, Output> criteria =
                                Criteria.builder()
                                        .setTypes(Input.class, Output.class)
                                        .optModelUrls(modelUrl)
                                        .build();
                        ZooModel<Input, Output> model = ModelZoo.loadModel(criteria);
                        String actualModelName;
                        if (modelName == null || modelName.isEmpty()) {
                            actualModelName = model.getName();
                        } else {
                            actualModelName = modelName;
                        }
                        actualModelName = actualModelName.replaceAll("(\\W|^_)", "_");

                        ModelInfo modelInfo =
                                new ModelInfo(
                                        actualModelName,
                                        modelUrl,
                                        model,
                                        configManager.getJobQueueSize());
                        modelInfo.setBatchSize(batchSize);
                        modelInfo.setMaxBatchDelay(maxBatchDelay);
                        ModelInfo existingModel = models.putIfAbsent(actualModelName, modelInfo);
                        if (existingModel != null) {
                            // model already exists
                            model.close();
                            throw new BadRequestException(
                                    "Model " + actualModelName + " is already registered.");
                        }
                        logger.info("Model {} loaded.", modelInfo.getModelName());

                        return modelInfo;
                    } catch (ModelException | IOException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    /**
     * Unregisters a model by its name.
     *
     * @param modelName the model name to be unregistered
     * @return {@code true} if unregister success
     */
    public boolean unregisterModel(String modelName) {
        ModelInfo model = models.remove(modelName);
        if (model == null) {
            logger.warn("Model not found: " + modelName);
            return false;
        }

        model.setMinWorkers(0);
        model.setMaxWorkers(0);
        wlm.modelChanged(model);
        startupModels.remove(modelName);
        model.close();
        logger.info("Model {} unregistered.", modelName);
        return true;
    }

    /**
     * Update model workers.
     *
     * @param modelName the model name to be updated
     * @param minWorkers the minimum number of workers
     * @param maxWorkers the maximum number of workers
     */
    public void updateModel(String modelName, int minWorkers, int maxWorkers) {
        ModelInfo model = models.get(modelName);
        if (model == null) {
            throw new AssertionError("Model not found: " + modelName);
        }
        model.setMinWorkers(minWorkers);
        model.setMaxWorkers(maxWorkers);
        logger.debug("updateModel: {}, count: {}", modelName, minWorkers);
        wlm.modelChanged(model);
    }

    /**
     * Returns the registry of all models.
     *
     * @return the registry of all models
     */
    public Map<String, ModelInfo> getModels() {
        return models;
    }

    /**
     * Returns a set of models that was loaded at startup.
     *
     * @return a set of models that was loaded at startup
     */
    public Set<String> getStartupModels() {
        return startupModels;
    }

    /**
     * Adds an inference job to the job queue.
     *
     * @param job an inference job to be executed
     * @return {@code true} if submit success
     * @throws ModelNotFoundException if the model is not registered
     */
    public boolean addJob(Job job) throws ModelNotFoundException {
        String modelName = job.getModelName();
        ModelInfo model = models.get(modelName);
        if (model == null) {
            throw new ModelNotFoundException("Model not found: " + modelName);
        }

        if (wlm.hasWorker(modelName)) {
            return model.addJob(job);
        }
        return false;
    }

    /**
     * Returns a list of worker information for specified model.
     *
     * @param modelName the model to be queried
     * @return a list of worker information for specified model
     * @throws ModelNotFoundException if specified model not found
     */
    public DescribeModelResponse describeModel(String modelName) throws ModelNotFoundException {
        ModelInfo model = models.get(modelName);
        if (model == null) {
            throw new ModelNotFoundException("Model not found: " + modelName);
        }

        DescribeModelResponse resp = new DescribeModelResponse();
        resp.setModelName(modelName);
        resp.setModelUrl(model.getModelUrl());
        resp.setBatchSize(model.getBatchSize());
        resp.setMaxBatchDelay(model.getMaxBatchDelay());
        resp.setMaxWorkers(model.getMaxWorkers());
        resp.setMinWorkers(model.getMinWorkers());
        resp.setLoadedAtStartup(startupModels.contains(modelName));

        int activeWorker = wlm.getNumRunningWorkers(modelName);
        int targetWorker = model.getMinWorkers();
        resp.setStatus(activeWorker >= targetWorker ? "Healthy" : "Unhealthy");

        List<WorkerThread> workers = wlm.getWorkers(modelName);
        for (WorkerThread worker : workers) {
            int workerId = worker.getWorkerId();
            long startTime = worker.getStartTime();
            boolean isRunning = worker.isRunning();
            int gpuId = worker.getGpuId();
            resp.addWorker(workerId, startTime, isRunning, gpuId);
        }
        return resp;
    }

    /**
     * Sends model server health status to client.
     *
     * @param ctx the client connection channel context
     */
    public void workerStatus(final ChannelHandlerContext ctx) {
        Runnable r =
                () -> {
                    String response = "Healthy";
                    int numWorking = 0;
                    int numScaled = 0;
                    for (Map.Entry<String, ModelInfo> m : models.entrySet()) {
                        numScaled += m.getValue().getMinWorkers();
                        numWorking += wlm.getNumRunningWorkers(m.getValue().getModelName());
                    }

                    if ((numWorking > 0) && (numWorking < numScaled)) {
                        response = "Partial Healthy";
                    } else if ((numWorking == 0) && (numScaled > 0)) {
                        response = "Unhealthy";
                    }

                    // TODO: Check if its OK to send other 2xx errors to ALB for "Partial Healthy"
                    // and "Unhealthy"
                    NettyUtils.sendJsonResponse(
                            ctx, new StatusResponse(response), HttpResponseStatus.OK);
                };
        wlm.scheduleAsync(r);
    }
}
