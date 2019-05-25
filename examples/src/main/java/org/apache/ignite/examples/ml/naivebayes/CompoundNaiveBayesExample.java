package org.apache.ignite.examples.ml.naivebayes;

import java.io.FileNotFoundException;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.ml.dataset.feature.extractor.Vectorizer;
import org.apache.ignite.ml.dataset.feature.extractor.impl.DummyVectorizer;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.naivebayes.compound.CompoundNaiveBayesModel;
import org.apache.ignite.ml.naivebayes.compound.CompoundNaiveBayesTrainer;
import org.apache.ignite.ml.naivebayes.discrete.DiscreteNaiveBayesTrainer;
import org.apache.ignite.ml.naivebayes.gaussian.GaussianNaiveBayesTrainer;
import org.apache.ignite.ml.selection.scoring.evaluator.Evaluator;
import org.apache.ignite.ml.util.MLSandboxDatasets;
import org.apache.ignite.ml.util.SandboxMLCache;

import static java.util.Arrays.asList;

/**
 * Run naive Compound Bayes classification model based on <a href="https://en.wikipedia.org/wiki/Naive_Bayes_classifier">
 * Nnaive Bayes classifier</a> algorithm ({@link GaussianNaiveBayesTrainer})and <a
 * href=https://en.wikipedia.org/wiki/Naive_Bayes_classifier#Multinomial_naive_Bayes"> Discrete naive Bayes
 * classifier</a> algorithm ({@link DiscreteNaiveBayesTrainer}) over distributed cache.
 * <p>
 * Code in this example launches Ignite grid and fills the cache with test data points.
 * <p>
 * After that it trains the naive Bayes classification model based on the specified data.</p>
 * <p>
 * Finally, this example loops over the test set of data points, applies the trained model to predict the target value,
 * compares prediction to expected outcome (ground truth), and builds
 * <a href="https://en.wikipedia.org/wiki/Confusion_matrix">confusion matrix</a>.</p>
 * <p>
 * You can change the test data used in this example and re-run it to explore this algorithm further.</p>
 */
public class CompoundNaiveBayesExample {
    public static void main(String[] args) throws FileNotFoundException {
        System.out.println();
        System.out.println(">>> Compound Naive Bayes classification model over partitioned dataset usage example started.");
        // Start ignite grid.
        try (Ignite ignite = Ignition.start("examples/config/example-ignite.xml")) {
            System.out.println(">>> Ignite grid started.");

            IgniteCache<Integer, Vector> dataCache = new SandboxMLCache(ignite)
                .fillCacheWith(MLSandboxDatasets.MIXED_DATASET);

            double[] classProbabilities = new double[] {.5, .5};
            double[][] thresholds = new double[][] {{.5}, {.5}, {.5}, {.5}, {.5}};

            System.out.println(">>> Create new naive Bayes classification trainer object.");
            CompoundNaiveBayesTrainer trainer = new CompoundNaiveBayesTrainer()
                .setClsProbabilities(classProbabilities)
                .setGaussianNaiveBayesTrainer(new GaussianNaiveBayesTrainer()
                    .setFeatureIdsToSkip(asList(3, 4, 5, 6, 7)))
                .setDiscreteNaiveBayesTrainer(new DiscreteNaiveBayesTrainer()
                    .setBucketThresholds(thresholds)
                    .setFeatureIdsToSkip(asList(0, 1, 2)));
            System.out.println(">>> Perform the training to get the model.");

            Vectorizer<Integer, Vector, Integer, Double> vectorizer = new DummyVectorizer<Integer>()
                .labeled(Vectorizer.LabelCoordinate.FIRST);

            CompoundNaiveBayesModel mdl = trainer.fit(ignite, dataCache, vectorizer);

            System.out.println(">>> Compound Naive Bayes model: " + mdl);

            double accuracy = Evaluator.evaluate(
                dataCache,
                mdl,
                vectorizer
            ).accuracy();

            System.out.println("\n>>> Accuracy " + accuracy);

            System.out.println(">>> Compound Naive bayes model over partitioned dataset usage example completed.");
        }
    }
}
