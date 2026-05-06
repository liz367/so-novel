package com.pcdd.sonovel.handle;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.util.FileUtils;
import io.documentnode.epub4j.domain.*;
import io.documentnode.epub4j.epub.EpubReader;
import io.documentnode.epub4j.epub.EpubWriter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class BookMergeService {

    /**
     * 将新下载的章节合并到已有 EPUB 文件。
     * 策略：重叠章节替换内容，超出部分追加到末尾。
     *
     * @param chapterDir    新下载章节的临时目录
     * @param mergeFilePath 已有 EPUB 文件的绝对路径
     */
    @SneakyThrows
    public void mergeEpub(File chapterDir, String mergeFilePath) {
        // 1. 读取已有 EPUB
        EpubReader reader = new EpubReader();
        io.documentnode.epub4j.domain.Book oldBook = reader.readEpub(new java.io.FileInputStream(mergeFilePath));

        // 2. 收集所有章节：order -> [title, data]
        TreeMap<Integer, Object[]> allChapters = new TreeMap<>();

        // 3. 提取已有章节（spine[0] = 封面, spine[1..N] = 正文章节）
        List<SpineReference> spineRefs = oldBook.getSpine().getSpineReferences();
        for (int i = 1; i < spineRefs.size(); i++) {
            Resource res = spineRefs.get(i).getResource();
            String title = extractTitleFromHtml(res.getData());
            allChapters.put(i, new Object[]{title, res.getData()});
        }

        // 4. 读取新章节文件，覆盖/追加
        List<File> newFiles = FileUtils.sortFilesByName(chapterDir);
        for (File f : newFiles) {
            int order = Integer.parseInt(StrUtil.subBefore(FileUtil.mainName(f), "_", false));
            String title = StrUtil.subAfter(FileUtil.mainName(f), "_", false);
            allChapters.put(order, new Object[]{title, FileUtil.readBytes(f)});
        }

        // 5. 重建 EPUB
        io.documentnode.epub4j.domain.Book newBook = new io.documentnode.epub4j.domain.Book();

        // 复制元数据
        Metadata oldMeta = oldBook.getMetadata();
        Metadata newMeta = newBook.getMetadata();
        newMeta.addTitle(oldMeta.getFirstTitle());
        newMeta.setAuthors(oldMeta.getAuthors());
        if (!oldMeta.getDescriptions().isEmpty()) {
            newMeta.addDescription(oldMeta.getDescriptions().getFirst());
        }
        newMeta.setLanguage("zh");
        newMeta.setDates(List.of(new io.documentnode.epub4j.domain.Date(new java.util.Date())));
        newMeta.addPublisher("so-novel");
        newMeta.setRights(List.of("本电子书由 so-novel(https://github.com/freeok/so-novel) 制作生成。仅供交流使用，不得用于商业用途。"));

        // 复制封面
        Resource coverImage = oldBook.getCoverImage();
        if (coverImage != null) {
            newBook.setCoverImage(new Resource(coverImage.getData(), coverImage.getHref()));
            if (!spineRefs.isEmpty()) {
                Resource coverPage = spineRefs.getFirst().getResource();
                newBook.addSection("封面", new Resource(coverPage.getData(), coverPage.getHref()));
            }
        }

        // 添加所有章节
        int len = String.valueOf(allChapters.size()).length();
        int idx = 1;
        for (var entry : allChapters.entrySet()) {
            Object[] pair = entry.getValue();
            String title = (String) pair[0];
            byte[] data = (byte[]) pair[1];
            String id = StrUtil.padPre(String.valueOf(idx), len, '0');
            newBook.addSection(title, new Resource(data, id + ".html"));
            idx++;
        }

        // 6. 写入（覆盖原文件）
        new EpubWriter().write(newBook, new FileOutputStream(mergeFilePath));
        Console.log("<== 合并完成，共 {} 章，已写入 {}", allChapters.size(), mergeFilePath);
    }

    private String extractTitleFromHtml(byte[] htmlBytes) {
        try {
            String html = new String(htmlBytes, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html);
            Element titleEl = doc.selectFirst("title");
            if (titleEl != null && StrUtil.isNotBlank(titleEl.text())) {
                return titleEl.text();
            }
            Element h2 = doc.selectFirst("h2");
            if (h2 != null && StrUtil.isNotBlank(h2.text())) {
                return h2.text();
            }
        } catch (Exception e) {
            log.warn("提取章节标题失败: {}", e.getMessage());
        }
        return "未知章节";
    }
}
