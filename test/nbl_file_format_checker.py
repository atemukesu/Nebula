import sys
import os
import struct
import zstandard as zstd
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QPushButton, QTextEdit, QFileDialog, QMessageBox
)
from PyQt5.QtCore import Qt, QThread, pyqtSignal

# 错误信息存储类
class ValidationError:
    def __init__(self, offset: int, message: str):
        self.offset = offset
        self.hex_offset = f"0x{offset:08X}" if offset >= 0 else "全局错误"
        self.message = message

# 校验线程（避免GUI卡顿）
class NBLValidatorThread(QThread):
    progress_signal = pyqtSignal(str)
    result_signal = pyqtSignal(list)

    def __init__(self, file_path: str):
        super().__init__()
        self.file_path = file_path
        self.errors = []

    def add_error(self, offset: int, msg: str):
        self.errors.append(ValidationError(offset, msg))

    def read_struct(self, file, offset: int, fmt: str, desc: str) -> tuple | None:
        """从指定偏移读取结构化数据（小端序）"""
        try:
            file.seek(offset)
            size = struct.calcsize(fmt)
            data = file.read(size)
            if len(data) < size:
                self.add_error(offset, f"读取{desc}时字节不足，需要{size}字节，实际{len(data)}字节")
                return None
            return struct.unpack(f"<{fmt}", data)
        except Exception as e:
            self.add_error(offset, f"读取{desc}失败: {str(e)}")
            return None

    def validate_frame_data(self, f, frame_idx: int, frame_offset: int, frame_size: int, file_size: int):
        """校验单帧数据（Zstd解压+格式校验）"""
        self.progress_signal.emit(f"正在校验帧 {frame_idx} (偏移: 0x{frame_offset:08X})")
        
        # 1. 检查帧数据范围
        if frame_offset < 0 or frame_offset >= file_size:
            self.add_error(frame_offset, f"帧{frame_idx}偏移量超出文件范围: {frame_offset}")
            return
        if frame_size <= 0 or frame_offset + frame_size > file_size:
            self.add_error(frame_offset, f"帧{frame_idx}大小异常或超出文件范围: {frame_size}")
            return

        # 2. 读取压缩帧数据
        try:
            f.seek(frame_offset)
            compressed_data = f.read(frame_size)
            if len(compressed_data) != frame_size:
                self.add_error(frame_offset, f"帧{frame_idx}读取字节数不匹配，期望{frame_size}字节，实际{len(compressed_data)}字节")
                return
        except Exception as e:
            self.add_error(frame_offset, f"读取帧{frame_idx}压缩数据失败: {str(e)}")
            return

        # 3. Zstd解压
        try:
            dctx = zstd.ZstdDecompressor()
            decompressed_data = dctx.decompress(compressed_data)
        except zstd.ZstdError as e:
            self.add_error(frame_offset, f"帧{frame_idx}Zstd解压失败: {str(e)}")
            return
        except Exception as e:
            self.add_error(frame_offset, f"帧{frame_idx}解压过程异常: {str(e)}")
            return

        # 4. 校验解压后帧数据格式
        if len(decompressed_data) < 5:
            self.add_error(frame_offset, f"帧{frame_idx}解压后数据过短，需要至少5字节，实际{len(decompressed_data)}字节")
            return

        # 4.1 解析帧类型和粒子数
        frame_type = decompressed_data[0] & 0xFF
        particle_count = struct.unpack("<i", decompressed_data[1:5])[0]

        # 4.2 校验帧类型
        if frame_type not in (0, 1):
            self.add_error(frame_offset, f"帧{frame_idx}类型非法，期望0(I帧)或1(P帧)，实际{frame_type}")

        # 4.3 校验粒子数
        if particle_count < 0 or particle_count > 1000000:
            self.add_error(frame_offset, f"帧{frame_idx}粒子数异常，期望0-1000000，实际{particle_count}")

        # 4.4 校验帧数据长度（根据帧类型）
        min_data_len = 5  # frame_type(1) + particle_count(4)
        if frame_type == 0:  # I帧数据长度校验
            # I帧：每个粒子包含 x/y/z(各4) + r/g/b/a(各1) + size(2) + texID(1) + seqID(1) + id(4) = 24字节
            required_len = min_data_len + particle_count * 24
            if len(decompressed_data) < required_len:
                self.add_error(frame_offset, f"帧{frame_idx}(I帧)数据不足，需要{required_len}字节，实际{len(decompressed_data)}字节")
        else:  # P帧数据长度校验
            # P帧：每个粒子包含 dx/dy/dz(各2) + dr/dg/db/da(各1) + size(2) + texID(1) + seqID(1) + id(4) = 18字节
            required_len = min_data_len + particle_count * 18
            if len(decompressed_data) < required_len:
                self.add_error(frame_offset, f"帧{frame_idx}(P帧)数据不足，需要{required_len}字节，实际{len(decompressed_data)}字节")

    def run(self):
        """主线程执行完整校验"""
        self.errors = []
        file_path = self.file_path

        # 基础文件检查
        if not os.path.exists(file_path):
            self.add_error(-1, f"文件不存在: {file_path}")
            self.result_signal.emit(self.errors)
            return
        if not os.path.isfile(file_path):
            self.add_error(-1, f"不是有效的文件: {file_path}")
            self.result_signal.emit(self.errors)
            return

        try:
            with open(file_path, "rb") as f:
                file_size = os.path.getsize(file_path)
                current_offset = 0
                total_frames = 0
                frame_offsets = []
                frame_sizes = []

                # ===================== 1. 校验文件头 (48字节) =====================
                self.progress_signal.emit("校验文件头...")
                header_size = 48
                if file_size < header_size:
                    self.add_error(current_offset, f"文件过小，至少需要{header_size}字节的头部，实际{file_size}字节")
                    self.result_signal.emit(self.errors)
                    return

                # 1.1 Magic数校验
                magic_tuple = self.read_struct(f, current_offset, "8s", "Magic数")
                if magic_tuple is None:
                    self.result_signal.emit(self.errors)
                    return
                magic_str = magic_tuple[0].decode('ascii', errors='replace')
                if magic_str != "NEBULAFX":
                    self.add_error(current_offset, f"Magic数错误，期望'NEBULAFX'，实际'{magic_str}'")
                current_offset += 8

                # 1.2 跳过8-9字节
                current_offset += 2

                # 1.3 目标帧率
                target_fps_tuple = self.read_struct(f, current_offset, "H", "目标帧率")
                target_fps = 0
                if target_fps_tuple is not None:
                    target_fps = target_fps_tuple[0]
                    if target_fps <= 0 or target_fps > 120:
                        self.add_error(current_offset, f"目标帧率异常，期望1-120，实际{target_fps}")
                current_offset += 2

                # 1.4 总帧数
                total_frames_tuple = self.read_struct(f, current_offset, "i", "总帧数")
                total_frames = 0
                if total_frames_tuple is not None:
                    total_frames = total_frames_tuple[0]
                    if total_frames <= 0 or total_frames > 100000:
                        self.add_error(current_offset, f"总帧数异常，期望1-100000，实际{total_frames}")
                current_offset += 4

                # 1.5 纹理数量
                texture_count_tuple = self.read_struct(f, current_offset, "H", "纹理数量")
                texture_count = 0
                if texture_count_tuple is not None:
                    texture_count = texture_count_tuple[0]
                    if texture_count < 0 or texture_count > 1000:
                        self.add_error(current_offset, f"纹理数量异常，期望0-1000，实际{texture_count}")
                current_offset += 2

                # 1.6 包围盒Min/Max
                bbox_min_tuple = self.read_struct(f, 20, "fff", "包围盒最小值")
                bbox_max_tuple = self.read_struct(f, 32, "fff", "包围盒最大值")
                if bbox_min_tuple and bbox_max_tuple:
                    for i in range(3):
                        if bbox_max_tuple[i] < bbox_min_tuple[i]:
                            self.add_error(32 + i*4, f"包围盒最大值[{i}]小于最小值，max={bbox_max_tuple[i]}, min={bbox_min_tuple[i]}")
                current_offset = 48

                # ===================== 2. 校验纹理条目 =====================
                self.progress_signal.emit("校验纹理条目...")
                for tex_idx in range(texture_count):
                    # 纹理路径长度
                    path_len_tuple = self.read_struct(f, current_offset, "H", f"纹理{tex_idx}路径长度")
                    path_len = 0
                    if path_len_tuple is not None:
                        path_len = path_len_tuple[0]
                        if path_len < 0 or path_len > 1024:
                            self.add_error(current_offset, f"纹理{tex_idx}路径长度异常，期望0-1024，实际{path_len}")
                    current_offset += 2

                    # 纹理路径（UTF-8）
                    if path_len > 0:
                        try:
                            f.seek(current_offset)
                            path_data = f.read(path_len)
                            if len(path_data) < path_len:
                                self.add_error(current_offset, f"纹理{tex_idx}路径字节不足，需要{path_len}字节，实际{len(path_data)}字节")
                            else:
                                path_data.decode('utf-8')  # 仅校验编码
                        except UnicodeDecodeError:
                            self.add_error(current_offset, f"纹理{tex_idx}路径不是合法的UTF-8编码")
                        except Exception as e:
                            self.add_error(current_offset, f"读取纹理{tex_idx}路径失败: {str(e)}")
                    current_offset += path_len

                    # texID/seqID
                    self.read_struct(f, current_offset, "BB", f"纹理{tex_idx}的texID和seqID")
                    current_offset += 2

                # ===================== 3. 校验帧索引表 =====================
                self.progress_signal.emit("校验帧索引表...")
                frame_index_size = total_frames * 12
                if current_offset + frame_index_size > file_size:
                    self.add_error(current_offset, f"帧索引表字节不足，需要{frame_index_size}字节，剩余{file_size - current_offset}字节")
                else:
                    frame_offsets = []
                    frame_sizes = []
                    for frame_idx in range(total_frames):
                        # 帧偏移
                        frame_offset_tuple = self.read_struct(f, current_offset, "q", f"帧{frame_idx}偏移量")
                        frame_offset = 0
                        if frame_offset_tuple is not None:
                            frame_offset = frame_offset_tuple[0]
                            frame_offsets.append(frame_offset)
                        current_offset += 8

                        # 帧大小
                        frame_size_tuple = self.read_struct(f, current_offset, "i", f"帧{frame_idx}大小")
                        frame_size = 0
                        if frame_size_tuple is not None:
                            frame_size = frame_size_tuple[0]
                            frame_sizes.append(frame_size)
                            if frame_size <= 0:
                                self.add_error(current_offset, f"帧{frame_idx}大小异常: {frame_size}")
                        current_offset += 4

                # ===================== 4. 校验关键帧索引表 =====================
                self.progress_signal.emit("校验关键帧索引表...")
                # 关键帧数量
                keyframe_count_tuple = self.read_struct(f, current_offset, "i", "关键帧数量")
                keyframe_count = 0
                if keyframe_count_tuple is not None:
                    keyframe_count = keyframe_count_tuple[0]
                    current_offset += 4

                    if keyframe_count < 0 or keyframe_count > total_frames:
                        self.add_error(current_offset - 4, f"关键帧数量异常，期望0-{total_frames}，实际{keyframe_count}")
                    else:
                        # 关键帧列表
                        keyframe_list_size = keyframe_count * 4
                        if current_offset + keyframe_list_size > file_size:
                            self.add_error(current_offset, f"关键帧列表字节不足，需要{keyframe_list_size}字节，剩余{file_size - current_offset}字节")
                        else:
                            for kf_idx in range(keyframe_count):
                                kf_index_tuple = self.read_struct(f, current_offset, "i", f"关键帧{kf_idx}索引")
                                kf_index = 0
                                if kf_index_tuple is not None:
                                    kf_index = kf_index_tuple[0]
                                    if kf_index < 0 or kf_index >= total_frames:
                                        self.add_error(current_offset, f"关键帧{kf_idx}索引异常，超出帧数范围: {kf_index}")
                                current_offset += 4
                else:
                    current_offset += 4  # 即使读取失败，也要移动偏移量

                # ===================== 5. 校验帧数据（核心） =====================
                if total_frames > 0 and len(frame_offsets) == total_frames and len(frame_sizes) == total_frames:
                    self.progress_signal.emit(f"开始校验{total_frames}帧数据（含Zstd解压）...")
                    for frame_idx in range(total_frames):
                        self.validate_frame_data(f, frame_idx, frame_offsets[frame_idx], frame_sizes[frame_idx], file_size)
                else:
                    self.add_error(-1, "跳过帧数据校验：总帧数或帧索引表不完整")

                # ===================== 6. 最终校验 =====================
                if current_offset > file_size:
                    self.add_error(file_size, f"解析完成后偏移量超出文件范围，解析到{current_offset}字节，文件仅{file_size}字节")

        except Exception as e:
            self.add_error(-1, f"文件读取异常: {str(e)}")

        # 返回校验结果
        self.result_signal.emit(self.errors)

# 主窗口
class NBLValidatorWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.init_ui()
        self.validator_thread = None

    def init_ui(self):
        # 窗口基本设置
        self.setWindowTitle("NBL 文件格式校验工具")
        self.setGeometry(100, 100, 800, 600)

        # 中心部件
        central_widget = QWidget()
        self.setCentralWidget(central_widget)

        # 布局
        main_layout = QVBoxLayout(central_widget)
        main_layout.setSpacing(10)
        main_layout.setContentsMargins(15, 15, 15, 15)

        # 1. 文件选择区域
        file_layout = QHBoxLayout()
        file_label = QLabel("文件路径：")
        self.file_edit = QLineEdit()
        self.file_edit.setPlaceholderText("请选择或输入NBL文件路径")
        browse_btn = QPushButton("浏览")
        browse_btn.clicked.connect(self.browse_file)
        file_layout.addWidget(file_label)
        file_layout.addWidget(self.file_edit)
        file_layout.addWidget(browse_btn)
        main_layout.addLayout(file_layout)

        # 2. 操作按钮
        btn_layout = QHBoxLayout()
        self.validate_btn = QPushButton("开始校验")
        self.validate_btn.clicked.connect(self.start_validation)
        self.clear_btn = QPushButton("清空结果")
        self.clear_btn.clicked.connect(self.clear_result)
        btn_layout.addWidget(self.validate_btn)
        btn_layout.addWidget(self.clear_btn)
        main_layout.addLayout(btn_layout)

        # 3. 进度提示
        self.progress_label = QLabel("状态：未开始")
        main_layout.addWidget(self.progress_label)

        # 4. 结果显示区域
        result_label = QLabel("校验结果：")
        main_layout.addWidget(result_label)
        self.result_text = QTextEdit()
        self.result_text.setReadOnly(True)
        main_layout.addWidget(self.result_text)

    def browse_file(self):
        """文件选择对话框"""
        file_path, _ = QFileDialog.getOpenFileName(
            self, "选择NBL文件", "", "NBL文件 (*.nbl);;所有文件 (*.*)"
        )
        if file_path:
            self.file_edit.setText(file_path)

    def start_validation(self):
        """启动校验"""
        file_path = self.file_edit.text().strip()
        if not file_path:
            QMessageBox.warning(self, "警告", "请先选择或输入NBL文件路径")
            return

        # 禁用按钮，清空结果
        self.validate_btn.setEnabled(False)
        self.clear_btn.setEnabled(False)
        self.result_text.clear()
        self.progress_label.setText("状态：校验中...")

        # 创建并启动校验线程
        self.validator_thread = NBLValidatorThread(file_path)
        self.validator_thread.progress_signal.connect(self.update_progress)
        self.validator_thread.result_signal.connect(self.show_result)
        self.validator_thread.finished.connect(self.on_validation_finished)
        self.validator_thread.start()

    def update_progress(self, msg: str):
        """更新进度提示"""
        self.progress_label.setText(f"状态：{msg}")

    def show_result(self, errors: list):
        """显示校验结果"""
        if not errors:
            self.result_text.append("文件格式完全符合要求")
        else:
            self.result_text.append(f"发现 {len(errors)} 个错误：")
            for idx, err in enumerate(errors, 1):
                self.result_text.append(f"{idx}. {err.hex_offset}：{err.message}")

    def on_validation_finished(self):
        """校验完成后恢复UI状态"""
        self.progress_label.setText("状态：校验完成")
        self.validate_btn.setEnabled(True)
        self.clear_btn.setEnabled(True)

    def clear_result(self):
        """清空结果"""
        self.result_text.clear()
        self.progress_label.setText("状态：未开始")

if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = NBLValidatorWindow()
    window.show()
    # 等待用户关闭窗口（替代input()）
    sys.exit(app.exec_())
