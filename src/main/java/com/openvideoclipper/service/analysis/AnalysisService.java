package com.openvideoclipper.service.analysis;

import static com.openvideoclipper.utils.LogUtil.info;
import static com.openvideoclipper.utils.LogUtil.error;
import com.openvideoclipper.config.OvcConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class AnalysisService {

    @Inject
    Instance<AnalysisProvider> providers;

    @Inject
    OvcConfig config;

    public List<AnalysisProvider.AnalysisResult> runAll(Path videoPath, Path audioPath, List<String> enabledProviderIds) {
        List<AnalysisProvider.AnalysisResult> allResults = new ArrayList<>();
        
        List<AnalysisProvider> activeProviders = providers.stream()
                .filter(p -> p.isAvailable())
                .filter(p -> enabledProviderIds.contains(p.id()))
                .toList();

        for (AnalysisProvider provider : activeProviders) {
            try {
                info("[AnalysisService] Running " + provider.displayName() + "...");
                allResults.add(provider.analyze(videoPath, audioPath));
            } catch (Exception e) {
                error("[AnalysisService] " + provider.displayName() + " failed: " + e.getMessage(), e);
            }
        }
        
        return allResults;
    }
}
