package com.smartrag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParseService 的单元测试类 (不依赖Spring Context)
 * 专门测试 splitLongSentence 方法的功能
 */
class ParseServiceUnitTest {

    private ParseService parseService;

    @BeforeEach
    void setUp() {
        parseService = new ParseService();
        // 设置配置值
        ReflectionTestUtils.setField(parseService, "chunkSize", 1000);
        ReflectionTestUtils.setField(parseService, "overlapSize", 0);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 1);
        ReflectionTestUtils.setField(parseService, "bufferSize", 8192);
        ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);
        ReflectionTestUtils.setField(parseService, "aliyunOcrEnabled", false);
        ReflectionTestUtils.setField(parseService, "aliyunOcrCallbackToken", "");
        ReflectionTestUtils.setField(parseService, "serverPort", 8081);
        ReflectionTestUtils.setField(parseService, "serverContextPath", "");
    }

    @Test
    void testSplitLongSentence_BasicFunctionality() throws Exception {
        // 测试基本功能
        String sentence = "这是一个测试句子，用来验证分词效果。";
        int chunkSize = 15;

        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // 验证拼接后等于原文
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);
        
        System.out.println("=== 基本功能测试 ===");
        System.out.println("原文: " + sentence + " (长度: " + sentence.length() + ")");
        System.out.println("分块数量: " + result.size());
        for (int i = 0; i < result.size(); i++) {
            System.out.println("分块 " + i + ": " + result.get(i) + " (长度: " + result.get(i).length() + ")");
        }
    }

    @Test
    void testSplitLongSentence_EdgeCases() throws Exception {
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        // 测试空字符串
        @SuppressWarnings("unchecked")
        List<String> emptyResult = (List<String>) method.invoke(parseService, "", 100);
        assertTrue(emptyResult.isEmpty() || (emptyResult.size() == 1 && emptyResult.get(0).isEmpty()));

        // 测试单个字符
        @SuppressWarnings("unchecked")
        List<String> singleCharResult = (List<String>) method.invoke(parseService, "测", 10);
        assertEquals(1, singleCharResult.size());
        assertEquals("测", singleCharResult.get(0));

        // 测试很长的文本
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longText.append("这是第").append(i).append("段文本。");
        }
        
        @SuppressWarnings("unchecked")
        List<String> longResult = (List<String>) method.invoke(parseService, longText.toString(), 30);
        assertTrue(longResult.size() > 1);
        
        // 验证拼接
        String reconstructed = String.join("", longResult);
        assertEquals(longText.toString(), reconstructed);

        System.out.println("=== 边界情况测试 ===");
        System.out.println("长文本分块数量: " + longResult.size());
    }

    @Test
    void testSplitLongSentence_ChunkSizeValidation() throws Exception {
        String sentence = "这是用来测试分块大小限制的句子，包含标点符号和数字123。";
        
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        // 测试不同的分块大小
        int[] chunkSizes = {5, 10, 20, 50};
        
        for (int chunkSize : chunkSizes) {
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);
            
            // 验证每个分块（除了最后一个）都不超过限制
            for (int i = 0; i < result.size() - 1; i++) {
                assertTrue(result.get(i).length() <= chunkSize, 
                    "分块大小 " + chunkSize + " 时，分块 " + i + " 长度超限: " + result.get(i).length());
            }
            
            // 验证拼接结果
            String reconstructed = String.join("", result);
            assertEquals(sentence, reconstructed, "分块大小 " + chunkSize + " 时拼接结果不匹配");
            
            System.out.println("分块大小 " + chunkSize + " -> 分块数量: " + result.size());
        }
    }

    @Test
    void testSplitLongSentence_Performance() throws Exception {
        // 性能测试
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeText.append("这是一个用于性能测试的长句子，包含各种中文字符和标点符号。");
        }
        
        String sentence = largeText.toString();
        int chunkSize = 100;
        
        Method method = ParseService.class.getDeclaredMethod("splitLongSentence", String.class, int.class);
        method.setAccessible(true);

        long startTime = System.currentTimeMillis();
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) method.invoke(parseService, sentence, chunkSize);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        assertNotNull(result);
        assertTrue(result.size() > 1);
        
        // 验证拼接结果
        String reconstructed = String.join("", result);
        assertEquals(sentence, reconstructed);

        System.out.println("=== 性能测试 ===");
        System.out.println("原文长度: " + sentence.length());
        System.out.println("分块数量: " + result.size());
        System.out.println("处理时间: " + duration + "ms");
        
        // 性能断言：处理时间应该在合理范围内
        assertTrue(duration < 5000, "处理时间过长: " + duration + "ms");
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_AddsSentenceOverlap() throws Exception {
        ReflectionTestUtils.setField(parseService, "overlapSize", 10);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 1);

        String text = "第一句内容较长。第二句内容较长。第三句内容较长。第四句内容较长。";

        List<String> result = splitTextIntoChunksWithSemantics(text, 20);

        assertEquals(2, result.size());
        assertEquals("第一句内容较长。第二句内容较长。", result.get(0));
        assertTrue(result.get(1).startsWith("第二句内容较长。\n\n第三句内容较长。"));
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_MergesShortChunks() throws Exception {
        ReflectionTestUtils.setField(parseService, "overlapSize", 0);
        ReflectionTestUtils.setField(parseService, "minChunkSize", 10);

        String text = "标题\n\n第一句内容较长。第二句内容较长。第三句内容较长。";

        List<String> result = splitTextIntoChunksWithSemantics(text, 20);

        assertFalse(result.contains("标题"));
        assertTrue(result.get(0).startsWith("标题\n\n第一句内容较长。"));
        assertTrue(result.stream().allMatch(chunk -> chunk != null && !chunk.isBlank()));
    }

    @Test
    void testSplitTextIntoChunksWithSemantics_EmptyTextReturnsNoChunks() throws Exception {
        assertTrue(splitTextIntoChunksWithSemantics("", 16).isEmpty());
        assertTrue(splitTextIntoChunksWithSemantics("   \n\n  ", 16).isEmpty());
    }

    @Test
    void testBuildLiteParseCommand_UsesJsonOutputAndOcrOptions() throws Exception {
        ReflectionTestUtils.setField(parseService, "liteParseCommand", "lit");
        ReflectionTestUtils.setField(parseService, "liteParseOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "liteParseOcrLanguage", "chi_sim+eng");
        ReflectionTestUtils.setField(parseService, "liteParseTessdataPath", "");
        ReflectionTestUtils.setField(parseService, "liteParseMaxPages", 200);
        ReflectionTestUtils.setField(parseService, "liteParseDpi", 180);
        ReflectionTestUtils.setField(parseService, "liteParseNumWorkers", 2);

        Method method = ParseService.class.getDeclaredMethod("buildLiteParseCommand", Path.class, Path.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(parseService, Path.of("/tmp/input.pdf"), Path.of("/tmp/output.json"));

        assertEquals("lit", command.get(0));
        assertTrue(command.contains("parse"));
        assertTrue(command.contains("--format"));
        assertTrue(command.contains("json"));
        assertTrue(command.contains("--output"));
        assertTrue(command.contains(Path.of("/tmp/output.json").toString()));
        assertTrue(command.contains("--ocr-language"));
        assertTrue(command.contains("chi_sim+eng"));
        assertFalse(command.contains("--ocr-server-url"));
        assertTrue(command.contains("--num-workers"));
        assertTrue(command.contains("2"));
        assertFalse(command.contains("--no-ocr"));
        assertFalse(command.contains("--tessdata-path"));
    }

    @Test
    void testBuildLiteParseCommand_AutoUsesInternalAliyunAdapterWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(parseService, "liteParseCommand", "lit");
        ReflectionTestUtils.setField(parseService, "liteParseOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "liteParseOcrLanguage", "chi_sim");
        ReflectionTestUtils.setField(parseService, "aliyunOcrEnabled", true);
        ReflectionTestUtils.setField(parseService, "aliyunOcrCallbackToken", "token 1");
        ReflectionTestUtils.setField(parseService, "serverPort", 8081);
        ReflectionTestUtils.setField(parseService, "serverContextPath", "");
        ReflectionTestUtils.setField(parseService, "liteParseMaxPages", 200);
        ReflectionTestUtils.setField(parseService, "liteParseDpi", 300);
        ReflectionTestUtils.setField(parseService, "liteParseNumWorkers", 0);

        Method method = ParseService.class.getDeclaredMethod("buildLiteParseCommand", Path.class, Path.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(parseService, Path.of("/tmp/input.pdf"), Path.of("/tmp/output.json"));

        assertTrue(command.contains("--ocr-server-url"));
        assertTrue(command.contains("http://127.0.0.1:8081/api/v1/internal/ocr/liteparse?token=token+1"));
    }

    @Test
    void testNormalizeLiteParseText_CleansChineseOcrSpacingAndPageFooter() throws Exception {
        String text = """
                哥们 明年

                  我 靠 SmartRAG 拿 到 的 日 常实习

                  学 习 这人 么 快噢直接 用

                  我 把 知 识库放 第 一 个了，  感 觉 面试 官 都 围绕这
                  问
                  个

                  No. 2 / 37
                """;

        String normalized = normalizeLiteParseText(text);

        assertTrue(normalized.contains("哥们明年"));
        assertTrue(normalized.contains("我靠SmartRAG拿到的日常实习"));
        assertTrue(normalized.contains("学习这人么快噢直接用"));
        assertTrue(normalized.contains("我把知识库放第一个了，感觉面试官都围绕这"));
        assertFalse(normalized.contains("No. 2 / 37"));
    }

    @Test
    void testNormalizeLiteParseText_CleansAliyunOcrPageOneOutputBeforeEmbedding() throws Exception {
        String text = """
                    SmartRAG-企业知识库检索测试

                ⼤家好，这是测试⽂档。

                SmartRAG是 2025年 9 ⽉份上线的，截⽌到⽬前，已经取得了⾮常瞩⽬的成绩，我这⾥晒⼀下哈。


                你好，目前靠着知识库检索+rag项目+同学分
                享优质面经侥幸oc了深圳招银网络和合肥科大讯飞
                java，但是十月还想冲一冲大厂，请问有机会吗，其
                实上面两家都是我线下面oc的，我个人也感觉自己
                线下发挥会好一些，想问问历年大中厂在10月国庆
                后还会陆续开展线下双选会的情况吗，谢谢解答。


                14：26


                那必须有










                No. 1 / 37%
                """;

        String normalized = normalizeLiteParseText(text);

        assertTrue(normalized.contains("SmartRAG是2025年9⽉份上线的"));
        assertTrue(normalized.contains("你好，目前靠着知识库检索+rag项目+同学分"));
        assertTrue(normalized.contains("享优质面经侥幸oc了深圳招银网络和合肥科大讯飞"));
        assertTrue(normalized.contains("那必须有"));
        assertFalse(normalized.contains("No. 1 / 37"));
        assertFalse(normalized.contains("\n\n\n"));
    }

    @Test
    void previewNormalizeLiteParseText_PrintsBeforeAndAfter() throws Exception {
        String text = """
                哥们 明年

                  我 靠 SmartRAG 拿 到 的 日 常实习

                  学 习 这人 么 快噢直接 用

                  我 把 知 识库放 第 一 个了，  感 觉 面试 官 都 围绕这
                  问
                  个

                  No. 2 / 37
                """;

        System.out.println("=== LiteParse OCR 清洗前 ===");
        System.out.println(text);
        System.out.println("=== LiteParse OCR 清洗后 ===");
        System.out.println(normalizeLiteParseText(text));
    }

    @Test
    void previewNormalizeLiteParseText_PageOneSample() throws Exception {
        String text = """
                你好，  目 前 靠 着 知识 库 检 索       项    目
                第                                             一
                                             rag            同学分
                享     面经侥幸                   络
                优质     oc     了 深圳 招 银 网             和 合肥 科大讯 飞
                java， 但 是十月   还 想 冲 一 冲 大  厂 ，请    问有 机 会   吗 ，其
                实 上 面 两家 都 是 我 线 下     的   ， 我 个    人也       自己
                    面oc                                      感觉
                                                  大中厂在     月 国庆
                线 下 发 挥 会好 一  些 ，想 问问 历年       10
                后 还 会 陆续 开展 线 下 双 选 会 的 情况     吗    ，谢 谢 解 答

                                           14:26

                                               有
                                               有

                                               No. 1 / 37
                """;

        System.out.println("=== LiteParse OCR 第1页清洗前 ===");
        System.out.println(text);
        System.out.println("=== LiteParse OCR 第1页清洗后 ===");
        System.out.println(normalizeLiteParseText(text));
    }

    @SuppressWarnings("unchecked")
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) throws Exception {
        Method method = ParseService.class.getDeclaredMethod("splitTextIntoChunksWithSemantics", String.class, int.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(parseService, text, chunkSize);
    }

    private String normalizeLiteParseText(String text) throws Exception {
        Method method = ParseService.class.getDeclaredMethod("normalizeLiteParseText", String.class);
        method.setAccessible(true);
        return (String) method.invoke(parseService, text);
    }
}
