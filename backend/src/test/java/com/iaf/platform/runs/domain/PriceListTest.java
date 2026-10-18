package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.PriceList.Quote;

/** kind: PriceList: flat prices and prices by the value of one input field. */
class PriceListTest {

    static final PriceList LIST = PriceList.parse("""
            apiVersion: iaf.platform/v1
            kind: PriceList
            metadata: { name: compute-prices, owner: team-compute }
            spec:
              currency: EUR
              prices:
                - { provider: cloud, action: vm.create, by: size.cpu, monthly: { 2: 30, 4: 60 } }
                - { provider: cloud, action: disk.create, monthly: 4.5 }
            """);

    @Test
    void aFlatPriceAnswersEveryCallOfTheAction() {
        assertThat(LIST.quote("cloud", "disk.create", SampleContent.json(Map.of()))).contains(new Quote(4.5, "EUR", "compute-prices"));
        assertThat(LIST.quote("cloud", "disk.create", null)).isPresent();
        assertThat(LIST.quote("cloud", "disk.delete", null)).isEmpty();
        assertThat(LIST.quote("other", "disk.create", null)).isEmpty();
    }

    @Test
    void aPricePerValueReadsTheNamedInputFieldAndMatchesWholeNumbersWithoutTheirFraction() {
        assertThat(LIST.quote("cloud", "vm.create", SampleContent.json(Map.of("size", Map.of("cpu", 4))))).map(Quote::monthly).contains(60.0);
        assertThat(LIST.quote("cloud", "vm.create", SampleContent.json(Map.of("size", Map.of("cpu", 2.0))))).map(Quote::monthly).contains(30.0);
        assertThat(LIST.quote("cloud", "vm.create", SampleContent.json(Map.of("size", Map.of("cpu", "4"))))).map(Quote::monthly).contains(60.0);
        assertThat(LIST.quote("cloud", "vm.create", SampleContent.json(Map.of("size", Map.of("cpu", 16))))).as("a value the list does not name").isEmpty();
        assertThat(LIST.quote("cloud", "vm.create", SampleContent.json(Map.of("cpu", 4)))).as("the field is size.cpu").isEmpty();
        assertThat(LIST.quote("cloud", "vm.create", null)).isEmpty();
    }

    @Test
    void theFirstListThatPricesACallWins() {
        PriceList cheaper = PriceList.parse("""
                kind: PriceList
                metadata: { name: a-discount }
                spec: { prices: [ { provider: cloud, action: disk.create, monthly: 3 } ] }
                """);
        assertThat(cheaper.currency()).as("the default currency").isEqualTo("USD");
        assertThat(PriceList.quote(List.of(cheaper, LIST), "cloud", "disk.create", null)).contains(new Quote(3, "USD", "a-discount"));
        assertThat(PriceList.quote(List.of(cheaper, LIST), "cloud", "vm.create", SampleContent.json(Map.of("size", Map.of("cpu", 2)))))
                .contains(new Quote(30, "EUR", "compute-prices"));
        assertThat(PriceList.quote(List.of(), "cloud", "disk.create", null)).isEmpty();
    }

    @Test
    void aBrokenListIsRefusedWithWhatIsWrong() {
        assertThatThrownBy(() -> PriceList.parse("kind: Provider\nmetadata: { name: x }")).hasMessage("kind must be PriceList, was Provider");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nspec: { prices: [] }")).hasMessage("metadata.name is required");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nmetadata: { name: x }\nspec: {}"))
                .hasMessage("spec.prices is a list of { provider, action, monthly }");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nmetadata: { name: x }\nspec: { prices: [ { action: a, monthly: 1 } ] }"))
                .hasMessage("spec.prices[0] needs a provider and an action");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nmetadata: { name: x }\nspec: { prices: [ { provider: p, action: a, monthly: { M10: 1 } } ] }"))
                .hasMessage("spec.prices[0]: a price per value needs by, the input field it depends on");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nmetadata: { name: x }\nspec: { prices: [ { provider: p, action: a, by: tier, monthly: { M10: cheap } } ] }"))
                .hasMessage("spec.prices[0].monthly.M10 must be a number");
        assertThatThrownBy(() -> PriceList.parse("kind: PriceList\nmetadata: { name: x }\nspec: { prices: [ { provider: p, action: a, monthly: lots } ] }"))
                .hasMessageContaining("spec.prices[0].monthly must be a number");
    }

    @Test
    void theRepositorysListPricesTheSampleCreateActions() throws Exception {
        PriceList prices = PriceList.parse(Files.readString(SampleContent.ROOT.resolve("providers/team-platform/list-prices.yaml")));
        assertThat(prices.name()).isEqualTo("list-prices");
        assertThat(prices.quote("vsphere", "vm.create", SampleContent.json(Map.of("cpu", 2)))).map(Quote::monthly).contains(38.5);
        assertThat(prices.quote("mongodb", "cluster.create", SampleContent.json(Map.of("tier", "M30")))).map(Quote::monthly).contains(210.0);
    }
}
