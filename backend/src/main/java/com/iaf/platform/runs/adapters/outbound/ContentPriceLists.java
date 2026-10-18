package com.iaf.platform.runs.adapters.outbound;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.domain.PriceList;
import com.iaf.platform.runs.ports.outbound.PriceListPort;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/** The price lists of the content repo: every {@code kind: PriceList} file, read at startup. */
@Slf4j
@Component
public class ContentPriceLists implements PriceListPort {

    private final PlatformProperties props;
    private volatile List<PriceList> lists = List.of();

    public ContentPriceLists(PlatformProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void load() {
        String dir = props.getContent() == null ? null : props.getContent().getDir();
        Path root = Path.of(dir == null || dir.isBlank() ? ".." : dir).toAbsolutePath().normalize();
        List<PriceList> found = new ArrayList<>();
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, PriceList.KIND)) {
            try {
                found.add(PriceList.parse(f.yaml()));
            } catch (IllegalArgumentException e) {
                log.warn("skipping price list {}: {}", f.relative(root), e.getMessage());
            }
        }
        found.sort(Comparator.comparing(PriceList::name));
        lists = List.copyOf(found);
        log.info("price lists: {} from {}", lists.size(), root);
    }

    @Override
    public Flux<PriceList> priceLists() {
        return Flux.fromIterable(lists);
    }
}
