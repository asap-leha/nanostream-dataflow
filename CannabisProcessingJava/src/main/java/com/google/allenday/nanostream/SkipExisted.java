package com.google.allenday.nanostream;

import com.google.allenday.genomics.core.gene.GeneData;
import com.google.allenday.genomics.core.gene.GeneExampleMetaData;
import com.google.allenday.genomics.core.io.FileUtils;
import com.google.allenday.genomics.core.io.GCSService;
import com.google.cloud.storage.BlobId;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SkipExisted extends DoFn<KV<GeneExampleMetaData, Iterable<GeneData>>, KV<GeneExampleMetaData, Iterable<GeneData>>> {

    private Logger LOG = LoggerFactory.getLogger(SkipExisted.class);
    private GCSService gcsService;

    private FileUtils fileUtils;
    private List<String> references;

    public SkipExisted(FileUtils fileUtils, List<String> references) {
        this.fileUtils = fileUtils;
        this.references = references;
    }

    @Setup
    public void setUp() {
        gcsService = GCSService.initialize(fileUtils);
    }

    private GeneExampleMetaData generateGeneExampleMetaDataFromCSVLine(String input) {
        String[] parts = input.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
        return new GeneExampleMetaData(parts[0], parts[1], parts[2], parts[3], parts[4], input);
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
        KV<GeneExampleMetaData, Iterable<GeneData>> element = c.element();
        try {
            GeneExampleMetaData geneExampleMetaData = element.getKey();

            for (String reference : references) {
                String name = geneExampleMetaData.getRun() + "_" + reference + ".sorted.bam";
                BlobId blobId = BlobId.of("cannabis-3k-results", "cannabis_processing_output/2019-09-30--09-17-27-UTC/result_sorted_bam/"
                        + name);
                if (!gcsService.isExists(blobId)) {
                    c.output(c.element());
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}