package com.orlando.watch.filter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.orlando.watch.config.WatchProcessingConfig;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时编译文件白名单规则，并用于匹配进入系统的文件名。
 */
@ApplicationScoped
@Slf4j
public class FileAllowListMatcher {

    private static final String REGEX_PREFIX = "re:";

    @Inject
    WatchProcessingConfig watchProcessingConfig;

    private Set<String> exactMatches;
    private List<Pattern> regexMatches;

    @PostConstruct
    void init() {
        exactMatches = new HashSet<>();
        regexMatches = new ArrayList<>();

        for (String rawRule : watchProcessingConfig.allowFileNames()) {
            if (rawRule == null || rawRule.isBlank()) {
                continue;
            }

            String rule = rawRule.trim();
            if (rule.startsWith(REGEX_PREFIX)) {
                addRegexRule(rule);
                continue;
            }
            exactMatches.add(rule);
        }

        log.info("白名单规则加载完成: exact={}, regex={}", exactMatches.size(), regexMatches.size());
    }

    public boolean isAllowed(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (exactMatches.contains(fileName)) {
            return true;
        }

        for (Pattern pattern : regexMatches) {
            if (pattern.matcher(fileName).matches()) {
                return true;
            }
        }
        return false;
    }

    private void addRegexRule(String rule) {
        String regexBody = rule.substring(REGEX_PREFIX.length()).trim();
        try {
            regexMatches.add(Pattern.compile(regexBody));
        } catch (PatternSyntaxException ex) {
            log.warn("白名单规则正则非法，已跳过: {}", rule);
        }
    }
}
