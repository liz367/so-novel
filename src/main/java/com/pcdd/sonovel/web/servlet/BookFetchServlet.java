package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.handle.BookMergeService;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.util.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.util.List;
import java.util.Set;

public class BookFetchServlet extends HttpServlet {

    private static final Set<String> ALLOWED_FORMATS = Set.of("epub", "txt", "html", "pdf");
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("zh_cn", "zh_tw", "zh_hant");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            String bookUrl = req.getParameter("url");
            String format = req.getParameter("format");
            String language = req.getParameter("language");
            String concurrencyStr = req.getParameter("concurrency");
            int id = SourceUtils.getRule(bookUrl).getId();

            if (StrUtil.isNotBlank(format) && !ALLOWED_FORMATS.contains(format.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的下载格式: " + format + "，可选: epub, txt, html, pdf");
                return;
            }

            if (StrUtil.isNotBlank(language) && !ALLOWED_LANGUAGES.contains(language.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的语言: " + language + "，可选: zh_CN, zh_TW, zh_Hant");
                return;
            }

            Integer concurrency = null;
            if (StrUtil.isNotBlank(concurrencyStr)) {
                concurrency = Integer.parseInt(concurrencyStr);
                int configConcurrency = AppConfigLoader.APP_CONFIG.getConcurrency();
                int maxAllowed = configConcurrency > 0 ? configConcurrency : 50;
                if (concurrency < 1 || concurrency > maxAllowed) {
                    RespUtils.writeError(resp, 400, "并发数须在 1~" + maxAllowed + " 之间");
                    return;
                }
            }

            // 解析增强功能参数
            String startChapterStr = req.getParameter("startChapter");
            String endChapterStr = req.getParameter("endChapter");
            String mergeFilePath = req.getParameter("mergeFilePath");

            int startChapter = 0;
            int endChapter = Integer.MAX_VALUE;
            if (StrUtil.isNotBlank(startChapterStr)) {
                startChapter = Integer.parseInt(startChapterStr);
            }
            if (StrUtil.isNotBlank(endChapterStr)) {
                endChapter = Integer.parseInt(endChapterStr);
            }
            if (startChapter < 0) startChapter = 0;
            if (endChapter < 0) endChapter = Integer.MAX_VALUE;
            if (startChapter > 0 && startChapter > endChapter) {
                RespUtils.writeError(resp, 400, "起始章节不能大于结束章节");
                return;
            }

            boolean hasMerge = StrUtil.isNotBlank(mergeFilePath);
            if (hasMerge) {
                File mergeFile = new File(mergeFilePath);
                if (!mergeFile.exists() || !mergeFile.isFile()) {
                    RespUtils.writeError(resp, 400, "指定的合并文件不存在");
                    return;
                }
                String extName = StrUtil.isNotBlank(format) ? format.toLowerCase() : AppConfigLoader.APP_CONFIG.getExtName();
                if (!extName.equals("epub")) {
                    RespUtils.writeError(resp, 400, "合并功能仅支持 EPUB 格式，请将下载格式设为 EPUB");
                    return;
                }
                if (!mergeFilePath.toLowerCase().endsWith(".epub")) {
                    RespUtils.writeError(resp, 400, "合并功能仅支持 EPUB 格式");
                    return;
                }
            }

            SearchResult sr = SearchResult.builder()
                    .sourceId(id)
                    .url(bookUrl)
                    .build();

            double totalTimeSeconds;
            boolean hasRange = startChapter > 0 || endChapter < Integer.MAX_VALUE;
            if (hasRange || hasMerge) {
                totalTimeSeconds = downloadWithRange(sr, format, language, concurrency, startChapter, endChapter, mergeFilePath);
            } else {
                totalTimeSeconds = downloadFileToServer(sr, format, language, concurrency);
            }

            if (totalTimeSeconds == 0) {
                RespUtils.writeError(resp, 500, "源站章节目录为空，中止下载");
            }

        } catch (Exception e) {
            RespUtils.writeError(resp, 500, e.getMessage());
        }
    }

    private double downloadFileToServer(SearchResult sr, String format, String language, Integer concurrency) {
        AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
        cfg.setSourceId(sr.getSourceId());

        if (StrUtil.isNotBlank(format)) {
            cfg.setExtName(format.toLowerCase());
        }
        if (StrUtil.isNotBlank(language)) {
            cfg.setLanguage(language);
        }
        if (concurrency != null) {
            cfg.setConcurrency(concurrency);
        }

        Console.log("<== 正在获取源站章节目录...");
        return new Crawler(cfg).crawl(sr.getUrl());
    }

    private double downloadWithRange(SearchResult sr, String format, String language, Integer concurrency,
                                      int startChapter, int endChapter, String mergeFilePath) {
        String extName = StrUtil.isNotBlank(format) ? format.toLowerCase() : AppConfigLoader.APP_CONFIG.getExtName();
        boolean hasMerge = StrUtil.isNotBlank(mergeFilePath);

        AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
        cfg.setSourceId(sr.getSourceId());
        if (hasMerge) {
            cfg.setExtName("epub");
        } else {
            cfg.setExtName(extName);
        }
        if (StrUtil.isNotBlank(language)) {
            cfg.setLanguage(language);
        }
        if (concurrency != null) {
            cfg.setConcurrency(concurrency);
        }

        Console.log("<== 正在获取源站章节目录...");
        TocParser tocParser = new TocParser(cfg);
        List<Chapter> toc = tocParser.parseAll(sr.getUrl());
        if (CollUtil.isEmpty(toc)) return 0;

        // 过滤章节范围（Chapter.order 从 1 开始）
        int start = Math.max(1, startChapter);
        int end = Math.min(endChapter, toc.size());
        List<Chapter> filteredToc = toc.stream()
                .filter(ch -> ch.getOrder() >= start && ch.getOrder() <= end)
                .toList();

        if (CollUtil.isEmpty(filteredToc)) {
            Console.error("<== 过滤后章节数为 0，范围: {}-{}", start, end);
            return 0;
        }

        Console.log("<== 过滤后共 {} 章 (范围: {}-{})", filteredToc.size(), start, end);

        Crawler crawler = new Crawler(cfg);

        if (hasMerge) {
            // 下载到临时目录（不执行后处理）
            File dir = crawler.crawlRaw(sr.getUrl(), filteredToc);
            if (dir == null) return 0;

            try {
                new BookMergeService().mergeEpub(dir, mergeFilePath);
            } finally {
                FileUtil.del(dir);
                com.pcdd.sonovel.context.BookContext.clear();
            }
            return 1;
        } else {
            return crawler.crawl(sr.getUrl(), filteredToc);
        }
    }

}