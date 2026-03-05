package com.orlando.watch.processor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class FolderRenameBatchProcessorPatternTest {

    @Test
    void shouldMatchUnicodeSeriesFileNameByConfiguredPattern() throws Exception {
        FolderRenameBatchProcessor processor = new FolderRenameBatchProcessor();
        Method method = FolderRenameBatchProcessor.class.getDeclaredMethod("buildEpisodeFilePattern", String.class);
        method.setAccessible(true);

        Pattern pattern = (Pattern) method.invoke(processor, "{name}_s{snumber}.e{enumber}.{ext}");
        assertNotNull(pattern);
        assertTrue(pattern.matcher("Ranma ½_s01.e01.mp4").matches());
    }
}
