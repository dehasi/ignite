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

package org.apache.ignite.ml.naivebayes.compound;

import java.io.Serializable;
import java.util.Arrays;
import org.apache.ignite.ml.Exportable;
import org.apache.ignite.ml.Exporter;
import org.apache.ignite.ml.IgniteModel;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.naivebayes.discrete.DiscreteNaiveBayesModel;
import org.apache.ignite.ml.naivebayes.gaussian.GaussianNaiveBayesModel;

/** Created by Ravil on 04/02/2019. */
public class CompoundNaiveBayesModel implements IgniteModel<Vector, Double>, Exportable<CompoundNaiveBayesModel>, Serializable {

    private final DiscreteNaiveBayesModel discreteModel;
    private final int discreteFeatureFrom;
    private final int discreteFeatureTo;
    private double[][][] probabilities;
    /** Prior probabilities of each class */
    private double[] clsProbabilities;
    /** Labels. */
    private double[] labels;
    private double[][] bucketThresholds;

    private final GaussianNaiveBayesModel gaussianModel;
    private final int gaussianFeatureFrom;
    private final int gaussianFeatureTo;

    public CompoundNaiveBayesModel(Builder builder) {
        gaussianModel = builder.gaussianModel;
        gaussianFeatureFrom = builder.gaussianFeatureFrom;
        gaussianFeatureTo = builder.gaussianFeatureTo;
        discreteModel = builder.discreteModel;
        discreteFeatureFrom = builder.discreteFeatureFrom;
        discreteFeatureTo = builder.discreteFeatureTo;
    }

    /** {@inheritDoc} */
    @Override public <P> void saveModel(Exporter<CompoundNaiveBayesModel, P> exporter, P path) {
        exporter.save(this, path);
    }

    @Override public Double predict(Vector vector) {
        double maxProbapilityPower = -Double.MAX_VALUE;
        double[] probapilityPowers = new double[vector.size()];
        Arrays.fill(probapilityPowers, Double.MAX_VALUE);
        int maxLabelIndex = 0;

        for (int i = 0; i < clsProbabilities.length; i++) {
            probapilityPowers[i] = Math.log(clsProbabilities[i]);
            for (int j = discreteFeatureFrom; j < discreteFeatureTo; j++) {
                int x = toBucketNumber(vector.get(j), bucketThresholds[j]);
                double p = probabilities[i][j][x];
                probapilityPowers[i] += (p > 0 ? Math.log(p) : .0);
            }

            for (int j = gaussianFeatureFrom; j < gaussianFeatureTo; j++) {
                double x = vector.get(j);
                double g = gauss(x, gaussianModel.getMeans()[i][j], gaussianModel.getVariances()[i][j]);
                probapilityPowers[i] += (g > 0 ? Math.log(g) : .0);
            }
        }

        for (int i = 0; i < probapilityPowers.length; i++) {
            if (probapilityPowers[i] > probapilityPowers[maxLabelIndex]) {
                maxLabelIndex = i;
            }
        }
        return labels[maxLabelIndex];
    }

    /** Returs a bucket number to which the {@code value} corresponds. */
    private int toBucketNumber(double val, double[] thresholds) {
        for (int i = 0; i < thresholds.length; i++) {
            if (val < thresholds[i])
                return i;
        }

        return thresholds.length;
    }

    /** Gauss distribution */
    private double gauss(double x, double mean, double variance) {
        return Math.exp(-1. * Math.pow(x - mean, 2) / (2. * variance)) / Math.sqrt(2. * Math.PI * variance);
    }

    public static CompoundNaiveBayesModel.Builder builder() {
        return new Builder();
    }

    static class Builder {

        private DiscreteNaiveBayesModel discreteModel;
        private int discreteFeatureFrom = -1;
        private int discreteFeatureTo = -1;

        private GaussianNaiveBayesModel gaussianModel;
        private int gaussianFeatureFrom = -1;
        private int gaussianFeatureTo = -1;

        Builder withGaussianModel(GaussianNaiveBayesModel gaussianModel) {
            this.gaussianModel = gaussianModel;
            return this;
        }

        Builder withGaussianModelRange(int from, int to) {
            assert from > to;
            gaussianFeatureFrom = from;
            gaussianFeatureTo = to;
            return this;
        }

        Builder withDiscreteModel(DiscreteNaiveBayesModel discreteModel) {
            this.discreteModel = discreteModel;
            return this;
        }

        CompoundNaiveBayesModel build() {
            if (discreteModel != null && (discreteFeatureFrom < 0 || discreteFeatureTo < 0)) {
                throw new IllegalArgumentException();
            }
            if (gaussianModel != null && (gaussianFeatureFrom < 0 || gaussianFeatureTo < 0)) {
                throw new IllegalArgumentException();
            }

            return new CompoundNaiveBayesModel(this);
        }
    }
}
