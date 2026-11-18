/* ============================================================
   suicoco — .suicj 插件包解包器
   .suicj = ZIP 容器（自有格式，不兼容其他插件格式）
   浏览器端纯 JS 实现：解析 End of Central Directory →
   Central Directory → Local File Header，支持 STORE(0) 与
   DEFLATE(8, 借助 DecompressionStream('deflate-raw'))。
   ============================================================ */
const SuicjZip = (() => {

  const EOCD_SIG = 0x06054b50;
  const CEN_SIG = 0x02014b50;
  const LOC_SIG = 0x04034b50;

  /** 在缓冲区尾部搜索 EOCD 记录 */
  function findEOCD(buf) {
    const dv = new DataView(buf);
    const min = 22;
    for (let i = buf.byteLength - min; i >= Math.max(0, buf.byteLength - 65557); i--) {
      if (dv.getUint32(i, true) === EOCD_SIG) {
        return {
          entryCount: dv.getUint16(i + 10, true),
          cdOffset: dv.getUint32(i + 16, true),
          cdSize: dv.getUint32(i + 12, true)
        };
      }
    }
    throw new Error("非有效的 .suicj 插件包（未找到 ZIP 结束记录）");
  }

  /** 解析 Central Directory，返回条目列表 */
  function parseCentralDirectory(buf, eocd) {
    const dv = new DataView(buf);
    const decoder = new TextDecoder();
    const entries = [];
    let off = eocd.cdOffset;
    for (let n = 0; n < eocd.entryCount; n++) {
      if (dv.getUint32(off, true) !== CEN_SIG) throw new Error("插件包中央目录损坏");
      const method = dv.getUint16(off + 10, true);
      const compSize = dv.getUint32(off + 20, true);
      const uncompSize = dv.getUint32(off + 24, true);
      const nameLen = dv.getUint16(off + 28, true);
      const extraLen = dv.getUint16(off + 30, true);
      const commentLen = dv.getUint16(off + 32, true);
      const localOff = dv.getUint32(off + 42, true);
      const name = decoder.decode(new Uint8Array(buf, off + 46, nameLen));
      entries.push({ name, method, compSize, uncompSize, localOff });
      off += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
  }

  /** 从 Local File Header 定位数据区并提取 */
  async function extractEntry(buf, entry) {
    const dv = new DataView(buf);
    if (dv.getUint32(entry.localOff, true) !== LOC_SIG) throw new Error("插件包文件头损坏: " + entry.name);
    const nameLen = dv.getUint16(entry.localOff + 26, true);
    const extraLen = dv.getUint16(entry.localOff + 28, true);
    const dataOff = entry.localOff + 30 + nameLen + extraLen;
    const raw = new Uint8Array(buf, dataOff, entry.compSize);
    if (entry.method === 0) return raw;
    if (entry.method === 8) {
      if (typeof DecompressionStream === "undefined")
        throw new Error("当前环境不支持 DEFLATE 解压，请使用 zip -0 打包的 .suicj");
      const ds = new DecompressionStream("deflate-raw");
      const out = await new Response(new Blob([raw]).stream().pipeThrough(ds)).arrayBuffer();
      return new Uint8Array(out);
    }
    throw new Error("不支持的压缩方法: " + entry.method + " (" + entry.name + ")");
  }

  /**
   * 解包 .suicj 文件，返回 { name -> text } 映射
   * @param {ArrayBuffer} buf
   */
  async function unzip(buf) {
    const eocd = findEOCD(buf);
    const entries = parseCentralDirectory(buf, eocd);
    const decoder = new TextDecoder();
    const files = {};
    for (const e of entries) {
      if (e.name.endsWith("/")) continue; // 跳过目录条目
      const data = await extractEntry(buf, e);
      files[e.name] = decoder.decode(data);
    }
    return files;
  }

  return { unzip };
})();
