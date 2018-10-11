/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.naivebayes.gaussian;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ignite.ml.dataset.Dataset;
import org.apache.ignite.ml.dataset.DatasetBuilder;
import org.apache.ignite.ml.dataset.PartitionDataBuilder;
import org.apache.ignite.ml.dataset.primitive.context.EmptyContext;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.util.MapUtil;
import org.apache.ignite.ml.structures.LabeledVector;
import org.apache.ignite.ml.structures.LabeledVectorSet;
import org.apache.ignite.ml.structures.partition.LabeledDatasetPartitionDataBuilderOnHeap;
import org.apache.ignite.ml.trainers.SingleLabelDatasetTrainer;

/**
 * Trainer for the naive Bayes classification model.
 */
public class GaussianNaiveBayesTrainer extends SingleLabelDatasetTrainer<GaussianNaiveBayesModel> {

    private double[] priorProbabilities;
    private boolean equiprobableClasses;

    /**
     * Trains model based on the specified data.
     *
     * @param datasetBuilder Dataset builder.
     * @param featureExtractor Feature extractor.
     * @param lbExtractor Label extractor.
     * @return Model.
     */
    @Override public <K, V> GaussianNaiveBayesModel fit(DatasetBuilder<K, V> datasetBuilder,
        IgniteBiFunction<K, V, Vector> featureExtractor, IgniteBiFunction<K, V, Double> lbExtractor) {
        return updateModel(null, datasetBuilder, featureExtractor, lbExtractor);
    }

    /** {@inheritDoc} */
    @Override protected boolean checkState(GaussianNaiveBayesModel mdl) {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected <K, V> GaussianNaiveBayesModel updateModel(GaussianNaiveBayesModel mdl,
        DatasetBuilder<K, V> datasetBuilder, IgniteBiFunction<K, V, Vector> featureExtractor,
        IgniteBiFunction<K, V, Double> lbExtractor) {
        assert datasetBuilder != null;

        PartitionDataBuilder<K, V, EmptyContext, LabeledVectorSet<Double, LabeledVector>> partDataBuilder
            = new LabeledDatasetPartitionDataBuilderOnHeap<>(
            featureExtractor,
            lbExtractor
        );

        try (Dataset<EmptyContext, LabeledVectorSet<Double, LabeledVector>> dataset = datasetBuilder.build(
            (upstream, upstreamSize) -> new EmptyContext(),
            partDataBuilder
        )) {
            MeanHelper mean = computeMeans(dataset);

            List<Double> sortedFeatures = new ArrayList<>(mean.featureCountPerLbl.keySet());
            sortedFeatures.sort(Double::compareTo);

            int labelCount = sortedFeatures.size();
            int featureCount = mean.featureSumPerLbl.get(sortedFeatures.get(0)).length;

            double[][] means = new double[labelCount][featureCount];
            double[][] variances = new double[labelCount][featureCount];
            double[] classProbabilities = new double[labelCount];
            double[] labels = new double[labelCount];

            long datasetSize = mean.featureCountPerLbl.values().stream().mapToInt(i -> i).sum();
            Map<Double, double[]> meansHolder = new HashMap<>();

            int lbl = 0;
            for (Double label : sortedFeatures) {
                int count = mean.featureCountPerLbl.get(label);
                double[] tmp = mean.featureSumPerLbl.get(label);

                for (int i = 0; i < featureCount; i++) {
                    means[lbl][i] = tmp[i] / count;
                }

                meansHolder.put(label, means[lbl]);
                classProbabilities[lbl] = (double)count / datasetSize;
                labels[lbl] = label;
                ++lbl;
            }

            VarianceHelper variance = computeVariance(dataset, meansHolder);

            lbl = 0;
            for (Double label : sortedFeatures) {
                int count = mean.featureCountPerLbl.get(label);
                double[] tmp = variance.featureDiff.get(label);

                for (int i = 0; i < featureCount; i++) {
                    variances[lbl][i] = tmp[i] / (count - 1);
                }
                ++lbl;
            }
            if (equiprobableClasses) {
                int k = classProbabilities.length;
                for (int i = 0; i < k; i++) {
                    classProbabilities[i] = 1. / k;
                }
            }
            if (priorProbabilities != null) {
                assert classProbabilities.length == priorProbabilities.length;
                classProbabilities = priorProbabilities;
            }

            return new GaussianNaiveBayesModel(means, variances, classProbabilities, labels);
        }
        catch (Exception e) {
            throw new
                RuntimeException(e);
        }

    }

    /** Sets equal probability for all classes. */
    public GaussianNaiveBayesTrainer withEquiprobableClasses() {
        resetSettings();
        equiprobableClasses = true;
        return this;
    }

    /** Sets prior probabilities. */
    public GaussianNaiveBayesTrainer setPriorProbabilities(double[] priorProbabilities) {
        resetSettings();
        this.priorProbabilities = priorProbabilities.clone();
        return this;
    }

    /** Sets default settings. */
    public GaussianNaiveBayesTrainer resetSettings() {
        equiprobableClasses = false;
        priorProbabilities = null;
        return this;
    }

    /**
     * Calculates sums of all values of a particular feature and amount of rows for all labels
     */
    private MeanHelper computeMeans(Dataset<EmptyContext, LabeledVectorSet<Double, LabeledVector>> dataset) {
        return dataset.compute(
            data -> {
                MeanHelper res = new MeanHelper();
                for (int i = 0; i < data.rowSize(); i++) {
                    LabeledVector row = data.getRow(i);
                    Vector features = row.features();
                    Double label = (Double)row.label();

                    double[] toMeans;
                    double[] sqSum;

                    if (!res.featureSumPerLbl.containsKey(label)) {
                        toMeans = new double[features.size()];
                        Arrays.fill(toMeans, 0.);
                        res.featureSumPerLbl.put(label, toMeans);
                    }
                    if (!res.squaredSumPerLbl.containsKey(label)) {
                        sqSum = new double[features.size()];
                        res.squaredSumPerLbl.put(label, sqSum);
                    }
                    if (!res.featureCountPerLbl.containsKey(label)) {
                        res.featureCountPerLbl.put(label, 0);
                    }
                    res.featureCountPerLbl.put(label, res.featureCountPerLbl.get(label) + 1);

                    toMeans = res.featureSumPerLbl.get(label);
                    sqSum = res.squaredSumPerLbl.get(label);
                    for (int j = 0; j < features.size(); j++) {
                        double x = features.get(j);
                        toMeans[j] += x;
                        sqSum[j] += x*x;
                    }
                }
                return res;
            }, (a, b) -> {
                if (a == null)
                    return b == null ? new MeanHelper() : b;
                if (b == null)
                    return a;
                return a.merge(b);
            });
    }

    /**
     * Calculates squared diffs between each value of the feature and the mean.
     *
     * @param meansHolder means of all featured for all labels.
     */
    private VarianceHelper computeVariance(Dataset<EmptyContext, LabeledVectorSet<Double, LabeledVector>> dataset,
        Map<Double, double[]> meansHolder) {
        return dataset.compute(
            data -> {
                VarianceHelper res = new VarianceHelper(meansHolder);
                for (int i = 0; i < data.rowSize(); i++) {
                    LabeledVector row = data.getRow(i);
                    Vector features = row.features();
                    Double label = (Double)row.label();

                    double[] m = res.meansHolder.get(label);

                    double[] toVars;

                    if (!res.featureDiff.containsKey(label)) {
                        toVars = new double[features.size()];
                        Arrays.fill(toVars, 0.);
                        res.featureDiff.put(label, toVars);
                    }

                    toVars = res.featureDiff.get(label);
                    for (int j = 0; j < features.size(); j++) {
                        toVars[j] += (m[j] - features.get(j)) * (m[j] - features.get(j));
                    }
                }

                return res;
            }, (a, b) -> {
                if (a == null)
                    return b == null ? new VarianceHelper(meansHolder) : b;
                if (b == null)
                    return a;
                return a.merge(b);
            });
    }

    /** Service class is used to calculate meanses. */
    private static class MeanHelper implements Serializable {
        /** Serial version uid. */
        private static final long serialVersionUID = 1L;
        /** Sum of all values for all features for each label */
        ConcurrentHashMap<Double, double[]> featureSumPerLbl = new ConcurrentHashMap<>();

        /** Sum of all values for all features for each label */
        ConcurrentHashMap<Double, double[]> squaredSumPerLbl = new ConcurrentHashMap<>();
        /** Rows count for each label */
        ConcurrentHashMap<Double, Integer> featureCountPerLbl = new ConcurrentHashMap<>();

        /** Merge current */
        MeanHelper merge(MeanHelper other) {
            featureSumPerLbl = MapUtil.mergeMaps(featureSumPerLbl, other.featureSumPerLbl, this::sum, ConcurrentHashMap::new);
            squaredSumPerLbl = MapUtil.mergeMaps(squaredSumPerLbl, other.squaredSumPerLbl, this::sum, ConcurrentHashMap::new);
            featureCountPerLbl = MapUtil.mergeMaps(featureCountPerLbl, other.featureCountPerLbl, (i1, i2) -> i1 + i2, ConcurrentHashMap::new);
            return this;
        }

        private double[] sum(double[] arr1, double[] arr2) {
            for (int i = 0; i < arr1.length; i++) {
                arr1[i] += arr2[i];
            }
            return arr1;
        }
    }

    /** Service class is used to calculate variances. */
    private static class VarianceHelper implements Serializable {
        /** Serial version uid. */
        private static final long serialVersionUID = 1L;
        /** Means of each feature for each label */
        final Map<Double, double[]> meansHolder;
        /** Sum of a squared diff if a feature value and the mean. */
        ConcurrentHashMap<Double, double[]> featureDiff = new ConcurrentHashMap<>();

        /**
         * @param meansHolder means of all featured for all labels.
         */
        public VarianceHelper(Map<Double, double[]> meansHolder) {
            this.meansHolder = meansHolder;
        }

        /** Merge current */
        VarianceHelper merge(VarianceHelper other) {
            featureDiff = MapUtil.mergeMaps(featureDiff, other.featureDiff, this::sum, ConcurrentHashMap::new);
            return this;
        }

        private double[] sum(double[] arr1, double[] arr2) {
            for (int i = 0; i < arr1.length; i++) {
                arr1[i] += arr2[i];
            }
            return arr1;
        }
    }
}
