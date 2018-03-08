/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.privacy.forgetme.logs.processor;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StrSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.privacy.forgetme.api.report.ReportAppender;
import org.wso2.carbon.privacy.forgetme.api.user.UserIdentifier;
import org.wso2.carbon.privacy.forgetme.logs.LogProcessorConstants;
import org.wso2.carbon.privacy.forgetme.logs.beans.Patterns;
import org.wso2.carbon.privacy.forgetme.logs.exception.LogProcessorException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.LineNumberReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log Processor to process Log Files.
 */
public class LogFileProcessor {

    private static Logger log = LoggerFactory.getLogger(LogFileProcessor.class);

    private final static Charset ENCODING = StandardCharsets.UTF_8;
    private static final String TEMP_FILE_PREFIX = "anon-";

    public void processFiles(UserIdentifier userIdentifier, ReportAppender reportAppender,
                             List<Patterns.Pattern> patternList, List<File> fileList) throws LogProcessorException {

        Map<String, String> templatePatternData = getTemplatePatternData(userIdentifier);
        List<MatchAndReplace> compiledPatterns = compile(patternList, templatePatternData);
        for (File file : fileList) {
            reportAppender.appendSection("Starting File %s", file.getAbsolutePath());
            if (log.isDebugEnabled()) {
                log.debug("Reading log file {}.", file.getName());
            }
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), ENCODING);
                    LineNumberReader lineReader = new LineNumberReader(reader);
                    BufferedWriter writer = new BufferedWriter(new FileWriter(Paths.get(file.getParent(),
                            TEMP_FILE_PREFIX + System.currentTimeMillis() + "-" + file.getName()).toString()))) {
                String line;
                while ((line = lineReader.readLine()) != null) {
                    String replacement = line;
                    boolean patternMatched = false;

                    // Check the line for any detectPattern matches.
                    for (MatchAndReplace matchAndReplace : compiledPatterns) {
                        Matcher matcher = matchAndReplace.getPattern().matcher(replacement);
                        if (matcher.find()) {

                            // Pattern match hit.
                            patternMatched = true;
                            String formattedReplacePattern = StrSubstitutor
                                    .replace(matchAndReplace.getReplacePattern(), templatePatternData);

                            // Here, if the replacePattern is not empty replace the username occurrences in the
                            // line. If it is empty, it indicates that a possible match is found in the current line.
                            if (StringUtils.isNotBlank(formattedReplacePattern)) {
                                replacement = replacement.replaceAll(formattedReplacePattern, userIdentifier
                                        .getPseudonym());
                                reportAppender.append("Replaced, %d, %b", lineReader.getLineNumber(), true);
                                if (log.isDebugEnabled()) {
                                    log.debug("Replaced {}", lineReader.getLineNumber());
                                }
                            } else {
                                reportAppender.append("Not Replaced, %d, %b", lineReader.getLineNumber(), true);
                                if (log.isDebugEnabled()) {
                                    log.debug("Not replaced {}", lineReader.getLineNumber());
                                }
                            }
                        }
                    }
                    if (patternMatched) {
                        writer.write(replacement + '\n');
                    } else {
                        writer.write(line + '\n');
                    }
                }
            } catch (IOException ex) {
                log.error("Error occurred while file read/write operation.", ex);
                throw new LogProcessorException(ex);
            } catch (Exception ex) {
                throw new LogProcessorException("Error occurred while processing log file.", ex);
            }
            log.info("Completed scanning log file: {}", file);
            reportAppender.appendSectionEnd("Completed " + file);
        }
    }

    private List<MatchAndReplace> compile(List<Patterns.Pattern> patternList, Map<String, String> templatePatternData) {

        List<MatchAndReplace> result = new ArrayList<>(patternList.size());
        for (Patterns.Pattern pattern : patternList) {
            if (log.isDebugEnabled()) {
                log.debug("Compiling pattern {}.", pattern.getKey());
            }
            String formattedDetectPattern = StrSubstitutor.replace(pattern.getDetectPattern(), templatePatternData)
                    .trim();
            Pattern regexp = Pattern.compile(formattedDetectPattern);
            result.add(new MatchAndReplace(regexp, pattern.getReplacePattern()));
        }
        return result;
    }

    /**
     * Replace original log file with the generated temp file.
     *
     * @param filePath Log file path.
     * @throws LogProcessorException
     */
    private static void replaceFile(Path filePath) throws LogProcessorException {

        String fileName = filePath.getFileName().toString();
        if (Files.exists(filePath)) {

            try {
                log.info("Deleting File From The Configured Path: " + filePath.toString());

                Files.delete(filePath);
                Path tempFilePath = Paths.get(filePath + TEMP_FILE_PREFIX);
                Files.move(tempFilePath, tempFilePath.resolveSibling(fileName));
                log.info("Renamed the temp file '" + fileName + ".temp' to '" + fileName + "'");

            } catch (IOException ex) {
                throw new LogProcessorException("Error occurred while delete/rename file operation.", ex);
            }
        }
    }

    /**
     * Get actual data for configured templates in the regexes. E.g. ${username} in regex will be replaced with the
     * actual username.
     *
     * @param userIdentifier User to be replaced.
     * @return Map of templates and their corresponding values.
     */
    private Map<String, String> getTemplatePatternData(UserIdentifier userIdentifier) {

        HashMap<String, String> patternData = new HashMap<>();
        patternData.put(LogProcessorConstants.USERNAME, userIdentifier.getUsername());
        patternData.put(LogProcessorConstants.TENANT_DOMAIN, userIdentifier.getTenantDomain());
        patternData.put(LogProcessorConstants.TENANT_ID, String.valueOf(userIdentifier.getTenantId()));
        if (StringUtils.equalsIgnoreCase(LogProcessorConstants.PRIMARY_USERSTORE_DOMAIN,
                userIdentifier.getUserStoreDomain())) {
            patternData.put(LogProcessorConstants.USERSTORE_DOMAIN, "");
        } else {
            patternData.put(LogProcessorConstants.USERSTORE_DOMAIN,
                    StringUtils.capitalize(userIdentifier.getUserStoreDomain()));
        }
        return patternData;
    }

    /**
     * Class to hold Regex Match pattern and its counterpart replacement pattern.
     */
    private static class MatchAndReplace {

        private Pattern pattern;
        private String replacePattern;

        public MatchAndReplace(Pattern pattern, String replacePattern) {

            this.pattern = pattern;
            this.replacePattern = replacePattern;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public String getReplacePattern() {
            return replacePattern;
        }
    }
}