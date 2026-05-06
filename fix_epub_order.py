"""
修复 EPUB 章节目录紊乱问题。
用法: .venv/Scripts/python.exe fix_epub_order.py <input.epub> [output.epub]

功能:
1. 从标题提取章节号，按章节号排序
2. 去除重复章节（保留正确的那个）
3. 非章节内容（公告、请假等）保留在相邻章节之间
4. 重建 OPF spine、manifest 和 NCX 目录
"""

import sys
import os
import re
import zipfile
import copy
import shutil
import xml.etree.ElementTree as ET
from collections import defaultdict


def extract_chapter_num(title: str) -> int | None:
    """从标题提取章节号，返回 None 表示非章节内容"""
    m = re.match(r'第(\d+)章', title.strip())
    return int(m.group(1)) if m else None


def parse_toc_ncx(ncx_content: str) -> list[dict]:
    """解析 toc.ncx，返回有序的 navPoint 列表"""
    # 使用正则提取，避免 XML 命名空间问题
    pattern = (
        r'<navPoint[^>]*id="([^"]*)"[^>]*playOrder="(\d+)"[^>]*>'
        r'.*?<text>(.*?)</text>'
        r'.*?<content\s+src="([^"]*)"\s*/?>'
        r'.*?</navPoint>'
    )
    entries = []
    for m in re.finditer(pattern, ncx_content, re.DOTALL):
        entries.append({
            'id': m.group(1),
            'play_order': int(m.group(2)),
            'title': m.group(3).strip(),
            'src': m.group(4),
        })
    return entries


def build_sorted_entries(entries: list[dict]) -> list[dict]:
    """
    对 entries 排序去重：
    - 章节内容按章节号排序，重复的保留第一个出现的
    - 非章节内容保留在排序后相邻章节之间
    """
    # 分离章节和非章节
    chapters = []  # (index_in_original, entry, chapter_num)
    non_chapters = []  # (index_in_original, entry)

    for i, e in enumerate(entries):
        ch_num = extract_chapter_num(e['title'])
        if ch_num is not None:
            chapters.append((i, e, ch_num))
        else:
            non_chapters.append((i, e))

    # 对章节按章节号排序，重复的只保留第一个
    seen = {}
    deduped_chapters = []
    for orig_idx, e, ch_num in chapters:
        if ch_num not in seen:
            seen[ch_num] = e
            deduped_chapters.append((orig_idx, e, ch_num))

    # 按章节号排序
    deduped_chapters.sort(key=lambda x: x[2])

    # 用章节号建立索引：orig_idx -> chapter_num (仅去重后保留的)
    kept_orig = {orig_idx for orig_idx, e, ch_num in deduped_chapters}
    # 所有章节（含重复）的 orig_idx -> chapter_num 映射
    all_ch_map = {orig_idx: ch_num for orig_idx, e, ch_num in chapters}

    # 对非章节内容，找原始序列中前、后最近的去重后保留的章节号
    # 用章节号（而非 orig_idx）作为锚点，避免重复章节导致锚点丢失
    sorted_ch_nums = [ch_num for _, _, ch_num in deduped_chapters]
    ch_num_to_idx = {ch_num: i for i, (_, _, ch_num) in enumerate(deduped_chapters)}

    insert_groups = defaultdict(list)  # anchor_ch_num -> [(orig_idx, entry)]
    non_ch_before_all = []  # 没有前置章节的非章节（如封面）

    for orig_idx, e in non_chapters:
        # 在原始 entries 中往前找最近的、且在去重后保留的章节
        anchor_ch = None
        for prev_idx in range(orig_idx - 1, -1, -1):
            if prev_idx in kept_orig:
                anchor_ch = all_ch_map[prev_idx]
                break
        if anchor_ch is not None:
            insert_groups[anchor_ch].append((orig_idx, e))
        else:
            non_ch_before_all.append((orig_idx, e))

    # 组装最终序列
    result = []
    # 先放没有前置章节的非章节（封面等）
    for orig_idx, e in sorted(non_ch_before_all):
        result.append(e)

    for ch_idx, (orig_idx, e, ch_num) in enumerate(deduped_chapters):
        result.append(e)
        # 插入跟随在该章节后面的非章节内容
        for nc_orig, nc_e in sorted(insert_groups.get(ch_num, [])):
            result.append(nc_e)

    return result


def rebuild_opf(opf_content: str, sorted_entries: list[dict], cover_id: str) -> str:
    """重建 content.opf：manifest 和 spine 按新顺序排列"""
    ns = {
        'opf': 'http://www.idpf.org/2007/opf',
        'dc': 'http://purl.org/dc/elements/1.1/',
    }

    # 解析 manifest 中已有的 item
    manifest_items = {}
    item_pattern = r'<opf:item\s+id="([^"]+)"\s+href="([^"]+)"\s+media-type="([^"]+)"'
    for m in re.finditer(item_pattern, opf_content):
        manifest_items[m.group(2)] = {'id': m.group(1), 'href': m.group(2), 'media_type': m.group(3)}

    # 确保 ncx 和 cover 在 manifest 中
    ncx_item = manifest_items.get('toc.ncx', {'id': 'ncx', 'href': 'toc.ncx', 'media_type': 'application/x-dtbncx+xml'})
    cover_item = manifest_items.get('cover.html', {'id': cover_id, 'href': 'cover.html', 'media_type': 'application/xhtml+xml'})
    cover_img_item = manifest_items.get('cover.jpg', {'id': 'image_1', 'href': 'cover.jpg', 'media_type': 'image/jpeg'})

    # 生成新的 manifest items
    new_manifest = f'    <opf:item id="{ncx_item["id"]}" href="{ncx_item["href"]}" media-type="{ncx_item["media_type"]}" />\n'
    new_manifest += f'    <opf:item id="{cover_img_item["id"]}" href="{cover_img_item["href"]}" media-type="{cover_img_item["media_type"]}" />\n'

    item_counter = 1
    entry_id_map = {}  # src -> new item id
    for e in sorted_entries:
        src = e['src']
        if src not in entry_id_map:
            new_id = f'item_{item_counter:04d}'
            entry_id_map[src] = new_id
            new_manifest += f'    <opf:item id="{new_id}" href="{src}" media-type="application/xhtml+xml" />\n'
            item_counter += 1

    # 生成新的 spine
    new_spine = ''
    for e in sorted_entries:
        new_spine += f'    <opf:itemref idref="{entry_id_map[e["src"]]}" />\n'

    # 替换 manifest 和 spine
    result = re.sub(
        r'<opf:manifest>.*?</opf:manifest>',
        f'<opf:manifest>\n{new_manifest}  </opf:manifest>',
        opf_content,
        flags=re.DOTALL,
    )
    result = re.sub(
        r'<opf:spine[^>]*>.*?</opf:spine>',
        f'<opf:spine toc="{ncx_item["id"]}">\n{new_spine}  </opf:spine>',
        result,
        flags=re.DOTALL,
    )

    return result


def rebuild_ncx(ncx_content: str, sorted_entries: list[dict]) -> str:
    """重建 toc.ncx"""
    # 提取头部和尾部
    head_match = re.search(r'^(.*?<navMap>)', ncx_content, re.DOTALL)
    tail_match = re.search(r'(</navMap>.*$)', ncx_content, re.DOTALL)

    if not head_match or not tail_match:
        raise ValueError('无法解析 toc.ncx 结构')

    header = head_match.group(1)
    footer = tail_match.group(1)

    nav_points = ''
    for i, e in enumerate(sorted_entries):
        nav_id = f'navPoint_{i + 1}'
        play_order = i + 1
        nav_points += (
            f'    <navPoint id="{nav_id}" playOrder="{play_order}">\n'
            f'      <navLabel>\n'
            f'        <text>{e["title"]}</text>\n'
            f'      </navLabel>\n'
            f'      <content src="{e["src"]}" />\n'
            f'    </navPoint>\n'
        )

    return f'{header}\n{nav_points}  {footer}'


def fix_epub(input_path: str, output_path: str | None = None):
    if output_path is None:
        base, ext = os.path.splitext(input_path)
        output_path = f'{base}_fixed{ext}'

    print(f'读取: {input_path}')
    print(f'输出: {output_path}')

    with zipfile.ZipFile(input_path, 'r') as zin:
        # 读取关键文件
        ncx_content = zin.read('OEBPS/toc.ncx').decode('utf-8')
        opf_content = zin.read('OEBPS/content.opf').decode('utf-8')
        container_content = zin.read('META-INF/container.xml').decode('utf-8')

        # 解析 TOC
        entries = parse_toc_ncx(ncx_content)
        print(f'原始条目数: {len(entries)}')

        # 统计
        ch_count = sum(1 for e in entries if extract_chapter_num(e['title']) is not None)
        print(f'  章节数: {ch_count}')
        print(f'  非章节数: {len(entries) - ch_count}')

        # 排序去重
        sorted_entries = build_sorted_entries(entries)

        sorted_ch_count = sum(1 for e in sorted_entries if extract_chapter_num(e['title']) is not None)
        print(f'\n修复后条目数: {len(sorted_entries)}')
        print(f'  章节数: {sorted_ch_count}')
        print(f'  去除重复: {ch_count - sorted_ch_count} 个')

        # 查找 cover item id
        cover_match = re.search(r'id="([^"]+)"\s+href="cover\.html"', opf_content)
        cover_id = cover_match.group(1) if cover_match else 'cover'

        # 重建 OPF 和 NCX
        new_opf = rebuild_opf(opf_content, sorted_entries, cover_id)
        new_ncx = rebuild_ncx(ncx_content, sorted_entries)

        # 写入新 EPUB
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED) as zout:
            # mimetype 必须是第一个文件且不压缩
            zout.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

            for item in zin.infolist():
                if item.filename in ('mimetype', 'OEBPS/toc.ncx', 'OEBPS/content.opf'):
                    continue
                zout.writestr(item, zin.read(item.filename))

            zout.writestr('OEBPS/content.opf', new_opf)
            zout.writestr('OEBPS/toc.ncx', new_ncx)

    print(f'\n修复完成: {output_path}')

    # 验证
    print('\n验证修复结果...')
    with zipfile.ZipFile(output_path, 'r') as z:
        ncx = z.read('OEBPS/toc.ncx').decode('utf-8')
        nav_points = parse_toc_ncx(ncx)
        prev_ch = 0
        violations = 0
        for e in nav_points:
            ch = extract_chapter_num(e['title'])
            if ch is not None:
                if ch < prev_ch:
                    violations += 1
                    print(f'  仍然紊乱: {e["title"][:40]}')
                prev_ch = ch
        if violations == 0:
            print('  章节顺序正确，无紊乱')
        else:
            print(f'  仍有 {violations} 处紊乱')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f'用法: {sys.argv[0]} <input.epub> [output.epub]')
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else None
    fix_epub(input_file, output_file)
