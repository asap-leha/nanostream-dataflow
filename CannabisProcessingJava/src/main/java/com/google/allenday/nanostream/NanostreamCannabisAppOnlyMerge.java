package com.google.allenday.nanostream;

import com.google.allenday.genomics.core.gene.GeneData;
import com.google.allenday.genomics.core.gene.GeneExampleMetaData;
import com.google.allenday.genomics.core.gene.GeneReadGroupMetaData;
import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.transform.AlignSortMergeTransform;
import com.google.allenday.genomics.core.transform.ValueIterableToValueListTransform;
import com.google.allenday.genomics.core.transform.fn.MergeFn;
import com.google.allenday.nanostream.cannabis_parsing.ParseCannabisDataFn;
import com.google.allenday.nanostream.di.NanostreamCannabisModule;
import com.google.allenday.nanostream.transforms.GroupByPairedReadsAndFilter;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

public class NanostreamCannabisAppOnlyMerge {

    private final static String JOB_NAME_PREFIX = "nanostream-cannabis--";

    public static boolean hasFilter(List<String> sraSamplesToFilter) {
        return sraSamplesToFilter != null && sraSamplesToFilter.size() > 0;
    }

    public static void main(String[] args) {

        NanostreamCannabisPipelineOptions pipelineOptions = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(NanostreamCannabisPipelineOptions.class);
        pipelineOptions.setNumberOfWorkerHarnessThreads(1);

//        pipelineOptions.setRunner(DirectRunner.class);

        StringBuilder jobNameBuilder = new StringBuilder(JOB_NAME_PREFIX);

        List<String> sraSamplesToFilter = pipelineOptions.getSraSamplesToFilter();
        if (hasFilter(sraSamplesToFilter)) {
            jobNameBuilder.append(String.join("-", sraSamplesToFilter));
            jobNameBuilder.append("--");
        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss-z");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String jobTime = simpleDateFormat.format(new Date());
        jobNameBuilder.append(jobTime);
        pipelineOptions.setJobName(jobNameBuilder.toString());

        Pipeline pipeline = Pipeline.create(pipelineOptions);
        Injector injector = Guice.createInjector(new NanostreamCannabisModule.Builder()
                .setFromOptions(pipelineOptions)
                .setJobTime(jobTime).build());

        PCollection<String> csvLines = pipeline
                .apply("Read data from CSV", TextIO.read().from(pipelineOptions.getInputCsvUri()));
        if (hasFilter(sraSamplesToFilter)) {
            csvLines = csvLines
                    .apply(Filter.by(name -> sraSamplesToFilter.stream().anyMatch(name::contains)));
        }
        csvLines
                .apply("Parse data", ParDo.of(injector.getInstance(ParseCannabisDataFn.class)))
                .apply(injector.getInstance(GroupByPairedReadsAndFilter.class))
                .apply(new ValueIterableToValueListTransform<>())
                .apply(MapElements.via(new SimpleFunction<KV<GeneExampleMetaData, List<GeneData>>,
                        KV<GeneReadGroupMetaData, KV<GeneExampleMetaData, List<GeneData>>>>() {
                    @Override
                    public KV<GeneReadGroupMetaData, KV<GeneExampleMetaData, List<GeneData>>> apply(KV<GeneExampleMetaData, List<GeneData>> input) {
                        return KV.of(input.getKey(), input);
                    }
                }))
                .apply(GroupByKey.create())
                .apply(new ValueIterableToValueListTransform<>())
                .apply(ParDo.of(new FilterExisted(new FileUtils(), pipelineOptions.getReferenceNamesList())))
                .apply(TextIO.write().withNumShards(1).to(String.format("gs://%s/%s", pipelineOptions.getResultBucket(),
                        "cannabis_processing_output/not_processed_sra")))
        ;

        pipeline.run();
    }
}
