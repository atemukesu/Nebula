import sys
import struct
import os
import time
import zstandard as zstd
from PyQt5.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                             QHBoxLayout, QPushButton, QTableWidget, QTableWidgetItem, 
                             QHeaderView, QFileDialog, QTextEdit, QLabel, QSplitter,
                             QProgressBar, QMessageBox)
from PyQt5.QtCore import Qt, QThread, pyqtSignal, QObject
from PyQt5.QtGui import QColor, QFont

# ==========================================
# 核心校验逻辑 (Validator Core)
# ==========================================

class NBLValidator:
    def __init__(self):
        self.log_messages = []

    def log(self, message):
        self.log_messages.append(message)

    def validate(self, file_path):
        self.log_messages = []
        self.metadata = {}
        self.log(f"开始检查文件: {os.path.basename(file_path)}")
        
        try:
            file_size = os.path.getsize(file_path)
            with open(file_path, 'rb') as f:
                # --- 1. File Header Check ---
                self.log(">> 正在检查文件头 (Header)...")
                header_data = f.read(48)
                if len(header_data) < 48:
                    return False, "文件过小，无法读取文件头", self.metadata

                magic, version, target_fps, total_frames, tex_count, attrs, \
                bbox_min_x, bbox_min_y, bbox_min_z, \
                bbox_max_x, bbox_max_y, bbox_max_z, \
                reserved = struct.unpack('<8sHHIHH3f3f4s', header_data)

                self.metadata['magic'] = magic.decode('ascii')
                self.metadata['version'] = version
                self.metadata['fps'] = target_fps
                self.metadata['total_frames'] = total_frames
                self.metadata['tex_count'] = tex_count
                self.metadata['attributes'] = attrs
                self.metadata['bbox_min'] = (bbox_min_x, bbox_min_y, bbox_min_z)
                self.metadata['bbox_max'] = (bbox_max_x, bbox_max_y, bbox_max_z)

                self.log(f"Header 信息: FPS={target_fps}, 总帧数={total_frames}, 贴图数={tex_count}")

                # --- 2. Texture Block Check ---
                self.log(">> 正在检查纹理定义块 (Texture Block)...")
                for i in range(tex_count):
                    # Read path length (uint16)
                    len_bytes = f.read(2)
                    if len(len_bytes) < 2:
                        return False, "纹理块读取意外中断 (EOF)", self.metadata
                    path_len = struct.unpack('<H', len_bytes)[0]
                    
                    # Read path
                    path_bytes = f.read(path_len)
                    if len(path_bytes) < path_len:
                        return False, f"纹理路径读取不完整 (Texture #{i})", self.metadata
                    
                    try:
                        path_str = path_bytes.decode('utf-8')
                    except UnicodeDecodeError:
                        return False, f"纹理路径不是有效的 UTF-8 编码 (Texture #{i})", self.metadata

                    # 读取行数和列数 (Rows/Cols)
                    f.read(2)
                    
                    self.metadata.setdefault('textures', []).append(path_str)
                
                # --- 3. Frame Index Table Check ---
                self.log(">> 正在检查帧索引表 (Frame Index Table)...")
                frame_indices = []
                for i in range(total_frames):
                    fi_data = f.read(12) # uint64 offset + uint32 size
                    if len(fi_data) < 12:
                         return False, f"帧索引表在第 {i} 帧处中断", self.metadata
                    chunk_offset, chunk_size = struct.unpack('<QI', fi_data)
                    
                    if chunk_offset + chunk_size > file_size:
                        return False, f"帧 {i} 的数据块越界 (Offset {chunk_offset} + Size {chunk_size} > FileSize)", self.metadata
                    
                    frame_indices.append((chunk_offset, chunk_size))

                # --- 4. Keyframe Index Table Check ---
                self.log(">> 正在检查关键帧索引表 (Keyframe Index Table)...")
                kf_count_bytes = f.read(4)
                if len(kf_count_bytes) < 4:
                    return False, "关键帧计数读取中断", self.metadata
                kf_count = struct.unpack('<I', kf_count_bytes)[0]
                
                # Skip reading indices content, just check file availability
                if kf_count > total_frames:
                     self.log(f"[Warning] 关键帧数量 ({kf_count}) 大于总帧数 ({total_frames})，这很奇怪。")

                self.metadata['kf_count'] = kf_count
                seek_amount = 4 * kf_count
                if f.tell() + seek_amount > file_size:
                    return False, "关键帧索引表数据越界", self.metadata
                f.seek(seek_amount, 1) # relative seek

                # --- 5. Frame Data Chunk Check (Deep Scan) ---
                self.log(">> 正在进行帧数据深度扫描 (Zstd解压与结构校验)...")
                dctx = zstd.ZstdDecompressor()
                
                for i, (offset, size) in enumerate(frame_indices):
                    f.seek(offset)
                    compressed_data = f.read(size)
                    
                    if len(compressed_data) != size:
                         return False, f"帧 {i} 数据读取不完整", self.metadata

                    try:
                        # 规范：每一帧独立压缩，全新上下文
                        decompressed = dctx.decompress(compressed_data)
                    except zstd.ZstdError as e:
                        return False, f"帧 {i} Zstd 解压失败: {str(e)} (可能是数据损坏或非独立压缩)", self.metadata

                    # Check Payload Structure
                    if len(decompressed) < 5:
                        return False, f"帧 {i} 解压后数据过短 (Header缺失)", self.metadata
                    
                    frame_type = decompressed[0]
                    particle_count = struct.unpack('<I', decompressed[1:5])[0]
                    
                    expected_payload_size = 0
                    
                    # Header is 5 bytes
                    header_size = 5 
                    
                    if frame_type == 0: # I-Frame
                        # SoA Layout:
                        # Pos (float*3) + Col (uint8*4) + Size (uint16) + TexID (uint8) + Seq (uint8) + PID (int32)
                        # 12N + 4N + 2N + 1N + 1N + 4N = 24N
                        expected_payload_size = particle_count * 24
                    elif frame_type == 1: # P-Frame
                        # SoA Layout:
                        # PosDelta (int16*3) + ColDelta (int8*4) + SizeDelta (int16) + TexIDDelta (int8) + SeqDelta (int8) + PID (int32)
                        # 6N + 4N + 2N + 1N + 1N + 4N = 18N
                        expected_payload_size = particle_count * 18
                    else:
                        return False, f"帧 {i} 未知的 FrameType: {frame_type}", self.metadata

                    actual_payload_size = len(decompressed) - header_size
                    if actual_payload_size != expected_payload_size:
                        return False, (f"帧 {i} 数据长度校验失败 (Type {frame_type}, N={particle_count})。\n"
                                       f"期望 Payload: {expected_payload_size} bytes, 实际: {actual_payload_size} bytes"), self.metadata

                self.log(">> 文件结构检查完毕，未发现严重错误。")
                return True, "检查通过 (Valid)", self.metadata

        except Exception as e:
            import traceback
            return False, f"发生未处理的异常: {str(e)}\n{traceback.format_exc()}", {}

# ==========================================
# 多线程工作类 (Worker)
# ==========================================

class ValidationWorker(QObject):
    finished = pyqtSignal()
    file_processed = pyqtSignal(str, bool, str, str, dict) # filepath, is_valid, status_text, log_text, metadata

    def __init__(self, file_paths):
        super().__init__()
        self.file_paths = file_paths
        self.is_running = True

    def run(self):
        validator = NBLValidator()
        for path in self.file_paths:
            if not self.is_running:
                break
            
            is_valid, status, metadata = validator.validate(path)
            log_text = "\n".join(validator.log_messages)
            
            # Emit result
            self.file_processed.emit(path, is_valid, status, log_text, metadata)
            
        self.finished.emit()

    def stop(self):
        self.is_running = False

# ==========================================
# 主界面 (GUI)
# ==========================================

class NBLCheckerApp(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("NBL 格式校验工具 (NBL Validator)")
        self.resize(1000, 700)
        
        # Styles
        self.pass_color = QColor(200, 255, 200)
        self.fail_color = QColor(255, 200, 200)
        self.font_mono = QFont("Consolas", 10)
        if not self.font_mono.exactMatch():
            self.font_mono = QFont("Courier New", 10)

        self.init_ui()
        self.files_map = {} # path -> row_index

    def init_ui(self):
        main_widget = QWidget()
        self.setCentralWidget(main_widget)
        layout = QVBoxLayout(main_widget)

        # Top Control Bar
        control_layout = QHBoxLayout()
        
        btn_add = QPushButton("添加文件 / Add Files")
        btn_add.clicked.connect(self.add_files)
        btn_add.setStyleSheet("padding: 8px; font-weight: bold;")
        
        btn_clear = QPushButton("清空列表 / Clear")
        btn_clear.clicked.connect(self.clear_list)
        
        self.btn_start = QPushButton("开始检查 / Start Check")
        self.btn_start.clicked.connect(self.start_validation)
        self.btn_start.setStyleSheet("background-color: #4CAF50; color: white; padding: 8px; font-weight: bold;")
        
        control_layout.addWidget(btn_add)
        control_layout.addWidget(btn_clear)
        control_layout.addStretch()
        control_layout.addWidget(self.btn_start)
        
        layout.addLayout(control_layout)

        # Splitter Area
        splitter = QSplitter(Qt.Horizontal)
        
        # Left: File Table
        self.table = QTableWidget()
        self.table.setColumnCount(3)
        self.table.setHorizontalHeaderLabels(["文件名", "状态", "简述"])
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Fixed)
        self.table.setColumnWidth(1, 100)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.table.setSelectionBehavior(QTableWidget.SelectRows)
        self.table.cellClicked.connect(self.show_log)
        splitter.addWidget(self.table)

        # Right: Log View
        log_widget = QWidget()
        log_layout = QVBoxLayout(log_widget)
        log_layout.setContentsMargins(0, 0, 0, 0)
        
        lbl_log = QLabel("详细日志 (点击左侧文件查看):")
        self.log_view = QTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setFont(self.font_mono)
        
        log_layout.addWidget(lbl_log)
        log_layout.addWidget(self.log_view)
        splitter.addWidget(log_widget)
        
        splitter.setStretchFactor(0, 3)
        splitter.setStretchFactor(1, 2)
        
        layout.addWidget(splitter)

        # Progress Bar
        self.progress = QProgressBar()
        layout.addWidget(self.progress)
        
        # Status Bar
        self.statusBar().showMessage("就绪。")

    def add_files(self):
        files, _ = QFileDialog.getOpenFileNames(self, "选择 NBL 文件", "", "NebulaFX Files (*.nbl);;All Files (*)")
        if files:
            for f in files:
                if f not in self.files_map:
                    row = self.table.rowCount()
                    self.table.insertRow(row)
                    
                    self.table.setItem(row, 0, QTableWidgetItem(os.path.basename(f)))
                    self.table.setItem(row, 1, QTableWidgetItem("等待中"))
                    self.table.setItem(row, 2, QTableWidgetItem(""))
                    
                    # Store full path in user role of first item
                    self.table.item(row, 0).setData(Qt.UserRole, f)
                    self.files_map[f] = row

    def clear_list(self):
        self.table.setRowCount(0)
        self.files_map.clear()
        self.log_view.clear()

    def start_validation(self):
        if self.table.rowCount() == 0:
            return

        files_to_check = []
        for i in range(self.table.rowCount()):
            path = self.table.item(i, 0).data(Qt.UserRole)
            self.table.setItem(i, 1, QTableWidgetItem("检查中..."))
            self.table.item(i, 1).setBackground(Qt.white)
            files_to_check.append(path)

        self.btn_start.setEnabled(False)
        self.progress.setRange(0, len(files_to_check))
        self.progress.setValue(0)

        # Thread setup
        self.thread = QThread()
        self.worker = ValidationWorker(files_to_check)
        self.worker.moveToThread(self.thread)

        self.thread.started.connect(self.worker.run)
        self.worker.file_processed.connect(self.update_row)
        self.worker.finished.connect(self.validation_finished)
        self.worker.finished.connect(self.thread.quit)
        self.worker.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.thread.deleteLater)

        self.thread.start()

    def update_row(self, path, is_valid, status, log_text, metadata):
        if path in self.files_map:
            row = self.files_map[path]
            
            status_item = QTableWidgetItem("通过" if is_valid else "失败")
            status_item.setBackground(self.pass_color if is_valid else self.fail_color)
            status_item.setTextAlignment(Qt.AlignCenter)
            
            self.table.setItem(row, 1, status_item)
            self.table.setItem(row, 2, QTableWidgetItem(status))
            
            # Store log in the filename item for retrieval
            self.table.item(row, 0).setData(Qt.UserRole + 1, log_text)
            self.table.item(row, 0).setData(Qt.UserRole + 2, metadata)
            
            self.progress.setValue(self.progress.value() + 1)
            
            # If current selection matches this row, update log view
            current_items = self.table.selectedItems()
            if current_items and current_items[0].row() == row:
                self.log_view.setText(log_text)

    def validation_finished(self):
        self.btn_start.setEnabled(True)
        self.statusBar().showMessage("检查完成。")
        QMessageBox.information(self, "完成", "批量检查已完成！")

    def show_log(self, row, col):
        item = self.table.item(row, 0)
        log = item.data(Qt.UserRole + 1)
        metadata = item.data(Qt.UserRole + 2)
        
        if log:
            display_text = ""
            if metadata:
                display_text += "=== 元数据 (Metadata) ===\n"
                display_text += f"版本 (Version): {metadata.get('version')}\n"
                display_text += f"目标帧率 (FPS): {metadata.get('fps')}\n"
                display_text += f"总帧数 (Total Frames): {metadata.get('total_frames')}\n"
                display_text += f"关键帧数 (Keyframes): {metadata.get('kf_count')}\n"
                display_text += f"贴图数量 (Textures): {metadata.get('tex_count')}\n"
                
                attrs = metadata.get('attributes', 0)
                attr_list = []
                if attrs & 0x01: attr_list.append("Alpha")
                if attrs & 0x02: attr_list.append("Size")
                display_text += f"属性掩码 (Attrs): {attrs} ({', '.join(attr_list)})\n"
                
                b_min = metadata.get('bbox_min', (0,0,0))
                b_max = metadata.get('bbox_max', (0,0,0))
                display_text += f"包围盒最小点 (BBox Min): [{b_min[0]:.2f}, {b_min[1]:.2f}, {b_min[2]:.2f}]\n"
                display_text += f"包围盒最大点 (BBox Max): [{b_max[0]:.2f}, {b_max[1]:.2f}, {b_max[2]:.2f}]\n"
                
                textures = metadata.get('textures', [])
                if textures:
                    display_text += "贴图列表 (Texture List):\n"
                    for i, t in enumerate(textures):
                        display_text += f"  [{i}] {t}\n"
                
                display_text += "\n" + "="*30 + "\n\n"
            
            display_text += "=== 检查日志 (Validation Log) ===\n"
            display_text += log
            self.log_view.setText(display_text)
        else:
            self.log_view.setText("（等待检查或无日志）")

if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = NBLCheckerApp()
    window.show()
    sys.exit(app.exec_())
