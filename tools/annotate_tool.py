# -*- coding: utf-8 -*-
"""打框标注工具（给标注员用，打包成 exe 分发）。

用法（标注员视角，越简单越好）：
  1. 打开图片文件夹 → 左侧选/加类别 → 图上按住鼠标拖框
  2. ←/→ 切换图片，Ctrl+Z 撤销上一个框，Del 删除选中框
  3. 每画一个框立即自动保存（图片文件夹下的 labels/*.txt，随时可关）
  4. 全部标完点"导出训练数据集" → 生成 Ultralytics 可直接训练的数据集

输出结构（自动标准化）：
  输出目录/
    images/train/ *.jpg        # 90% 训练图（按文件名哈希确定性划分）
    images/val/   *.jpg        # 10% 验证图
    labels/train/ *.txt        # YOLO 格式：class cx cy w h（全部归一化）
    labels/val/   *.txt
    classes.txt                # 类别表，每行一个（建议英文小写，训练友好）
    data.yaml                   # Ultralytics 训练直接指向它

运行：buildenv/Scripts/python.exe tools/annotate_tool.py
"""
import hashlib
import shutil
import sys
from pathlib import Path

SUPPORTED = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
VAL_RATIO = 0.1


# ---------------- 核心逻辑（无头可测） ----------------

def px_to_yolo(cls_id, x1, y1, x2, y2, iw, ih):
    """像素框 → YOLO 归一化行：class cx cy w h（保留 6 位小数）。"""
    x1, x2 = min(x1, x2), max(x1, x2)
    y1, y2 = min(y1, y2), max(y1, y2)
    x1 = max(0.0, min(x1, iw)); x2 = max(0.0, min(x2, iw))
    y1 = max(0.0, min(y1, ih)); y2 = max(0.0, min(y2, ih))
    cx = (x1 + x2) / 2 / iw
    cy = (y1 + y2) / 2 / ih
    w = (x2 - x1) / iw
    h = (y2 - y1) / ih
    return f"{int(cls_id)} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}"


def yolo_to_px(line, iw, ih):
    """YOLO 行 → 像素框 (cls, x1, y1, x2, y2)。坏行返回 None。"""
    p = line.split()
    if len(p) != 5:
        return None
    try:
        cls_id = int(p[0])
        cx, cy, w, h = (float(v) for v in p[1:])
    except ValueError:
        return None
    x1 = (cx - w / 2) * iw
    x2 = (cx + w / 2) * iw
    y1 = (cy - h / 2) * ih
    y2 = (cy + h / 2) * ih
    return cls_id, x1, y1, x2, y2


def list_images(img_dir):
    return sorted(p for p in Path(img_dir).iterdir()
                  if p.suffix.lower() in SUPPORTED)


def labels_dir(img_dir):
    return Path(img_dir) / "labels"


def save_label(img_dir, stem, lines):
    """把一张图的全部框写盘（原子替换）。lines 为空也写空文件=已标完无目标。"""
    labels_dir(img_dir).mkdir(exist_ok=True)
    f = labels_dir(img_dir) / f"{stem}.txt"
    tmp = f.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    tmp.replace(f)


def load_label(img_dir, stem, iw, ih):
    """读回一张图的框（像素坐标），没有标签文件返回 None（未标注）。"""
    f = labels_dir(img_dir) / f"{stem}.txt"
    if not f.exists():
        return None
    boxes = []
    for line in f.read_text(encoding="utf-8").splitlines():
        b = yolo_to_px(line, iw, ih)
        if b is not None:
            boxes.append(b)
    return boxes


def export_dataset(img_dir, out_dir, classes, val_ratio=VAL_RATIO):
    """把已标注的图编译成 Ultralytics 训练集。

    划分按文件名 md5 确定性进行（重跑结果一致）。
    返回 (train_n, val_n, skipped)：skipped = 还没标注过的图片数。
    """
    img_dir, out_dir = Path(img_dir), Path(out_dir)
    (out_dir / "images/train").mkdir(parents=True, exist_ok=True)
    (out_dir / "images/val").mkdir(parents=True, exist_ok=True)
    (out_dir / "labels/train").mkdir(parents=True, exist_ok=True)
    (out_dir / "labels/val").mkdir(parents=True, exist_ok=True)

    from PIL import Image
    train_n = val_n = skipped = 0
    for img_path in list_images(img_dir):
        stem = img_path.stem
        boxes = load_label(img_dir, stem, 0, 0)
        if boxes is None:
            skipped += 1
            continue
        with Image.open(img_path) as im:
            iw, ih = im.size
        # 重新按真实尺寸读一次（load_label 的 0,0 只判断存在性）
        lines = []
        f = labels_dir(img_dir) / f"{stem}.txt"
        for line in f.read_text(encoding="utf-8").splitlines():
            b = yolo_to_px(line, iw, ih)
            if b is not None:
                cls_id = b[0]
                if 0 <= cls_id < len(classes):
                    lines.append(px_to_yolo(cls_id, *b[1:], iw, ih))

        is_val = int(hashlib.md5(stem.encode()).hexdigest(), 16) % 1000 \
            < int(val_ratio * 1000)
        split = "val" if is_val else "train"
        shutil.copy2(img_path, out_dir / "images" / split / img_path.name)
        (out_dir / "labels" / split / f"{stem}.txt").write_text(
            "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
        if is_val:
            val_n += 1
        else:
            train_n += 1

    (out_dir / "classes.txt").write_text(
        "\n".join(classes) + "\n", encoding="utf-8")
    (out_dir / "data.yaml").write_text(
        f"path: {out_dir.resolve().as_posix()}\n"
        f"train: images/train\n"
        f"val: images/val\n"
        f"names:\n" + "".join(f"  {i}: {n}\n" for i, n in enumerate(classes)),
        encoding="utf-8")
    return train_n, val_n, skipped


# ---------------- 界面 ----------------

def app_dir():
    """classes.txt 的默认位置：exe 旁边（打包后）或脚本旁边。"""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).parent


PALETTE = ["#e74c3c", "#2980b9", "#27ae60", "#8e44ad", "#f39c12",
           "#16a085", "#d35400", "#2c3e50", "#c0392b", "#7f8c8d"]


def run_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, simpledialog
    from PIL import Image, ImageTk

    root = tk.Tk()
    root.title("打框标注工具 — 拖框标注，一键导出训练集")
    root.geometry("1280x820")

    st = {  # 全部状态
        "img_dir": None, "imgs": [], "idx": -1,
        "pil": None, "tkimg": None, "scale": 1.0, "ox": 0, "oy": 0,
        "boxes": [],            # [(cls, x1, y1, x2, y2)] 像素坐标
        "sel": -1, "drag": None,
        "classes": [], "cur_cls": -1,
        "classes_file": app_dir() / "classes.txt",
    }

    # ---- 顶栏 ----
    top = tk.Frame(root, bd=1, relief="raised")
    top.pack(side="top", fill="x")
    tk.Button(top, text="打开图片文件夹", width=16,
              command=lambda: open_folder()).pack(side="left", padx=6, pady=6)
    tk.Button(top, text="导出训练数据集", width=16, bg="#d5f5e3",
              command=lambda: do_export()).pack(side="left", padx=6)
    tk.Button(top, text="上一张 (←)", width=12,
              command=lambda: step(-1)).pack(side="left", padx=6)
    tk.Button(top, text="下一张 (→)", width=12,
              command=lambda: step(1)).pack(side="left", padx=6)
    tk.Button(top, text="撤销 (Ctrl+Z)", width=14,
              command=lambda: undo()).pack(side="left", padx=6)
    tk.Button(top, text="删除选中框 (Del)", width=16,
              command=lambda: delete_sel()).pack(side="left", padx=6)

    # ---- 左侧类别栏 ----
    left = tk.Frame(root, width=230, bd=1, relief="groove")
    left.pack(side="left", fill="y")
    tk.Label(left, text="类别（先加类别再画框）",
             font=("微软雅黑", 11, "bold")).pack(pady=6)
    cls_lb = tk.Listbox(left, width=28, exportselection=False)
    cls_lb.pack(fill="both", expand=True, padx=6)
    cls_lb.bind("<<ListboxSelect>>", lambda e: on_cls_pick())
    entry = tk.Entry(left)
    entry.pack(fill="x", padx=6, pady=4)
    entry.insert(0, "medicine box  （示例：英文小写）")
    tk.Button(left, text="＋ 添加类别",
              command=lambda: add_class()).pack(pady=4)

    # ---- 右侧框列表 ----
    right = tk.Frame(root, width=240, bd=1, relief="groove")
    right.pack(side="right", fill="y")
    tk.Label(right, text="本图的框", font=("微软雅黑", 11, "bold")).pack(pady=6)
    box_lb = tk.Listbox(right, width=30, exportselection=False)
    box_lb.pack(fill="both", expand=True, padx=6)
    box_lb.bind("<<ListboxSelect>>", lambda e: on_box_pick())

    # ---- 中央画布 ----
    canvas = tk.Canvas(root, bg="#2b2b2b", highlightthickness=0)
    canvas.pack(side="left", fill="both", expand=True)

    # ---- 底部状态栏 ----
    status = tk.Label(root, bd=1, relief="sunken", anchor="w",
                      text="先点左上角【打开图片文件夹】")
    status.pack(side="bottom", fill="x")

    # ---- 行为 ----
    def set_status(msg):
        status.config(text=msg)

    def load_classes():
        f = st["classes_file"]
        if f.exists():
            st["classes"] = [l.strip() for l in
                             f.read_text(encoding="utf-8").splitlines()
                             if l.strip()]
        refresh_cls_lb()

    def save_classes():
        st["classes_file"].write_text(
            "\n".join(st["classes"]) + "\n", encoding="utf-8")

    def refresh_cls_lb():
        cls_lb.delete(0, "end")
        for i, c in enumerate(st["classes"]):
            cls_lb.insert("end", f"{i}: {c}")
        if 0 <= st["cur_cls"] < len(st["classes"]):
            cls_lb.selection_set(st["cur_cls"])

    def add_class():
        name = entry.get().strip()
        # 占位提示不算
        if not name or "示例" in name:
            messagebox.showwarning("提示", "先在输入框里填类别名（建议英文小写，"
                                   "如 medicine box）")
            return
        if name in st["classes"]:
            messagebox.showinfo("提示", "这个类别已经存在")
            return
        st["classes"].append(name)
        st["cur_cls"] = len(st["classes"]) - 1
        save_classes()
        refresh_cls_lb()
        set_status(f"已添加类别「{name}」，现在可以在图上拖框了")

    def on_cls_pick():
        sel = cls_lb.curselection()
        if sel:
            st["cur_cls"] = sel[0]

    def open_folder():
        d = filedialog.askdirectory(title="选择图片文件夹")
        if not d:
            return
        st["img_dir"] = Path(d)
        st["imgs"] = list_images(d)
        if not st["imgs"]:
            messagebox.showwarning("提示", "文件夹里没有 jpg/png/bmp/webp 图片")
            return
        # 跳到第一张未标注的图
        st["idx"] = 0
        for i, p in enumerate(st["imgs"]):
            if not (labels_dir(d) / f"{p.stem}.txt").exists():
                st["idx"] = i
                break
        show()
        done = sum(1 for p in st["imgs"]
                   if (labels_dir(d) / f"{p.stem}.txt").exists())
        set_status(f"{len(st['imgs'])} 张图片，已标注 {done} 张"
                   f"（框会自动保存，随时可关）")

    def step(d):
        if not st["imgs"]:
            return
        st["idx"] = (st["idx"] + d) % len(st["imgs"])
        show()

    def show():
        p = st["imgs"][st["idx"]]
        st["pil"] = Image.open(p)
        iw, ih = st["pil"].size
        st["boxes"] = load_label(st["img_dir"], p.stem, iw, ih) or []
        st["sel"] = -1
        st["drag"] = None
        fit()
        refresh_box_lb()

    def fit():
        cw = canvas.winfo_width()
        ch = canvas.winfo_height()
        if cw < 10 or ch < 10 or st["pil"] is None:
            canvas.after(50, fit)
            return
        iw, ih = st["pil"].size
        st["scale"] = min(cw / iw, ch / ih)
        dw, dh = int(iw * st["scale"]), int(ih * st["scale"])
        st["ox"] = (cw - dw) // 2
        st["oy"] = (ch - dh) // 2
        resized = st["pil"].resize((dw, dh))
        st["tkimg"] = ImageTk.PhotoImage(resized)
        draw()

    def to_img(mx, my):
        return ((mx - st["ox"]) / st["scale"], (my - st["oy"]) / st["scale"])

    def to_scr(x, y):
        return (x * st["scale"] + st["ox"], y * st["scale"] + st["oy"])

    def draw():
        canvas.delete("all")
        if st["tkimg"] is None:
            return
        canvas.create_image(st["ox"], st["oy"], anchor="nw",
                            image=st["tkimg"])
        for i, (cls_id, x1, y1, x2, y2) in enumerate(st["boxes"]):
            sx1, sy1 = to_scr(x1, y1)
            sx2, sy2 = to_scr(x2, y2)
            color = PALETTE[cls_id % len(PALETTE)]
            w = 4 if i == st["sel"] else 2
            canvas.create_rectangle(sx1, sy1, sx2, sy2,
                                    outline=color, width=w)
            name = st["classes"][cls_id] if cls_id < len(st["classes"]) \
                else str(cls_id)
            canvas.create_text(sx1, max(sy1 - 12, 10), anchor="w",
                               text=name, fill=color,
                               font=("微软雅黑", 10, "bold"))
        if st["drag"]:
            x1, y1, x2, y2 = st["drag"]
            sx1, sy1 = to_scr(x1, y1)
            sx2, sy2 = to_scr(x2, y2)
            canvas.create_rectangle(sx1, sy1, sx2, sy2,
                                    outline="#ffe600", width=2)
        if st["imgs"]:
            p = st["imgs"][st["idx"]]
            done = (labels_dir(st["img_dir"]) / f"{p.stem}.txt").exists()
            canvas.create_text(
                st["ox"] + 8, st["oy"] + 8, anchor="nw",
                text=f"{st['idx'] + 1}/{len(st['imgs'])}  "
                     f"{p.name}  {'✓已标注' if done else '未标注'}",
                fill="#ffe600", font=("微软雅黑", 11, "bold"))

    def refresh_box_lb():
        box_lb.delete(0, "end")
        for i, (cls_id, x1, y1, x2, y2) in enumerate(st["boxes"]):
            name = st["classes"][cls_id] if cls_id < len(st["classes"]) \
                else str(cls_id)
            box_lb.insert("end", f"{name}  ({x1:.0f},{y1:.0f},"
                                 f"{x2:.0f},{y2:.0f})")

    def on_box_pick():
        sel = box_lb.curselection()
        st["sel"] = sel[0] if sel else -1
        draw()

    def persist():
        """当前图的框 → YOLO txt（每次变动立即调用）。"""
        if not st["imgs"] or st["pil"] is None:
            return
        p = st["imgs"][st["idx"]]
        iw, ih = st["pil"].size
        lines = [px_to_yolo(c, x1, y1, x2, y2, iw, ih)
                 for c, x1, y1, x2, y2 in st["boxes"]]
        save_label(st["img_dir"], p.stem, lines)

    def undo():
        if st["boxes"]:
            st["boxes"].pop()
            st["sel"] = -1
            persist()
            refresh_box_lb()
            draw()

    def delete_sel():
        if 0 <= st["sel"] < len(st["boxes"]):
            st["boxes"].pop(st["sel"])
            st["sel"] = -1
            persist()
            refresh_box_lb()
            draw()

    def do_export():
        if not st["img_dir"]:
            messagebox.showwarning("提示", "先打开图片文件夹")
            return
        if not st["classes"]:
            messagebox.showwarning("提示", "还没有任何类别")
            return
        persist()
        out = filedialog.askdirectory(title="选择导出位置（将生成训练数据集）")
        if not out:
            return
        train_n, val_n, skipped = export_dataset(
            st["img_dir"], Path(out), st["classes"])
        msg = (f"导出完成！\n\n训练图 {train_n} 张，验证图 {val_n} 张\n"
               f"还有 {skipped} 张未标注（未计入）\n\n"
               f"输出目录：{out}\n"
               f"训练时把 data.yaml 喂给 ultralytics 即可")
        messagebox.showinfo("导出完成", msg)
        set_status(f"已导出：train {train_n} / val {val_n}"
                   f"（{skipped} 张未标注跳过）→ {out}")

    # ---- 鼠标 ----
    def on_press(e):
        if st["pil"] is None:
            return
        if st["cur_cls"] < 0 or st["cur_cls"] >= len(st["classes"]):
            messagebox.showwarning("提示", "先在左侧选择或添加一个类别")
            return
        st["drag"] = [*to_img(e.x, e.y)] * 2

    def on_drag(e):
        if st["drag"]:
            x, y = to_img(e.x, e.y)
            st["drag"][2], st["drag"][3] = x, y
            draw()

    def on_release(e):
        if not st["drag"]:
            return
        x1, y1, x2, y2 = st["drag"]
        st["drag"] = None
        iw, ih = st["pil"].size
        if abs(x2 - x1) * st["scale"] < 8 or abs(y2 - y1) * st["scale"] < 8:
            draw()
            return                       # 太小视为误点
        cls_id = st["cur_cls"]
        st["boxes"].append((cls_id, x1, y1, x2, y2))
        persist()
        refresh_box_lb()
        draw()

    canvas.bind("<ButtonPress-1>", on_press)
    canvas.bind("<B1-Motion>", on_drag)
    canvas.bind("<ButtonRelease-1>", on_release)
    root.bind("<Left>", lambda e: step(-1))
    root.bind("<Right>", lambda e: step(1))
    root.bind("<Control-z>", lambda e: undo())
    root.bind("<Delete>", lambda e: delete_sel())
    root.bind("<Configure>", lambda e: fit())

    load_classes()
    root.mainloop()


if __name__ == "__main__":
    run_gui()
