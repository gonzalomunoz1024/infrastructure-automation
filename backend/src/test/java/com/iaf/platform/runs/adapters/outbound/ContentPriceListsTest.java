package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.PriceList;

import reactor.test.StepVerifier;

/** The price lists of the content repo: every kind: PriceList file, whatever folder it is in. */
class ContentPriceListsTest {

    static ContentPriceLists over(Path root) {
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir(root.toString());
        ContentPriceLists lists = new ContentPriceLists(props);
        lists.load();
        return lists;
    }

    @Test
    void theRepositorysPriceListIsRead() {
        StepVerifier.create(over(SampleContent.ROOT).priceLists().map(PriceList::name))
                .expectNext("list-prices")
                .verifyComplete();
    }

    @Test
    void aBrokenListIsSkippedAndTheOthersAreReadByName(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("providers/team-b"));
        Files.writeString(root.resolve("providers/team-b/zeta.yaml"), """
                kind: PriceList
                metadata: { name: zeta }
                spec: { prices: [ { provider: p, action: a, monthly: 1 } ] }
                """);
        Files.writeString(root.resolve("providers/team-b/alpha.yaml"), """
                kind: PriceList
                metadata: { name: alpha }
                spec: { prices: [ { provider: p, action: a, monthly: 2 } ] }
                """);
        Files.writeString(root.resolve("providers/team-b/broken.yaml"), """
                kind: PriceList
                metadata: { name: broken }
                spec: { prices: [ { provider: p, action: a, monthly: free } ] }
                """);

        StepVerifier.create(over(root).priceLists().map(PriceList::name))
                .expectNext("alpha", "zeta")
                .verifyComplete();
    }
}
