package top.touhikari;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 负责 TCP 模拟器的所有图形界面渲染和动画逻辑。
 */
public class TcpSimulatorUI {
    private final TcpSimulator simulator;
    
    // UI 组件
    private final BorderPane root;
    private final Pane animationPane;
    private final TextArea logArea;
    
    // 底部序号队列相关 UI
    private final ScrollPane queueScrollPane;
    private final Pane queuePane;
    private final Rectangle windowHighlight; // 滑动窗口高亮层
    private static final int QUEUE_BLOCK_SIZE = 30;
    private static final int QUEUE_SPACING = 2;
    private static final int QUEUE_CAPACITY = 100; // 扩大容量到 100，以便长时间演示

    // UI 常量与坐标
    private static final int SENDER_X = 80;
    private static final int RECEIVER_X = 650;
    private static final int TRANSIT_Y_BASE = 100;
    private static final double PACKET_SPEED_SEC = 2.0; // 动画变长一点显得更优雅

    // 超时重传定时器，记录已发送未确认的 seq 对应的定时器
    private final Map<Integer, Timer> timers = new HashMap<>();

    public TcpSimulatorUI(TcpSimulator simulator) {
        this.simulator = simulator;
        
        this.root = new BorderPane();
        this.animationPane = new Pane();
        this.logArea = new TextArea();
        
        this.queuePane = new Pane();
        this.queueScrollPane = new ScrollPane();
        this.windowHighlight = new Rectangle();
        
        initUI();
    }

    public BorderPane getRootPane() {
        return root;
    }

    private void initUI() {
        // 1. 顶部控制面板
        VBox topContainer = new VBox(10);
        topContainer.setPadding(new Insets(15));
        topContainer.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        
        HBox controls1 = new HBox(15);
        controls1.setAlignment(Pos.CENTER_LEFT);

        // 窗口大小设置
        Label lblWindow = new Label("窗口大小:");
        Spinner<Integer> windowSpinner = new Spinner<>(1, 10, simulator.getWindowSize());
        windowSpinner.setPrefWidth(70);
        windowSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (simulator.setWindowSize(newVal)) {
                log("[系统] 窗口大小调整为: " + newVal);
                updateWindowUI();
            } else {
                log("[警告] 无法缩小窗口，当前有超过新窗口大小的数据包正在传输！");
                Platform.runLater(() -> windowSpinner.getValueFactory().setValue(oldVal));
            }
        });

        // 批量发送设置
        Label lblBatch = new Label("批量发送数量:");
        Spinner<Integer> batchSpinner = new Spinner<>(1, 10, 1);
        batchSpinner.setPrefWidth(70);

        Button btnSend = new Button("发送");
        btnSend.setStyle("-fx-base: #b6e7c9;");
        btnSend.setOnAction(e -> handleBatchSendRequest(batchSpinner.getValue(), false, false));

        controls1.getChildren().addAll(lblWindow, windowSpinner, new Separator(), lblBatch, batchSpinner, btnSend);

        HBox controls2 = new HBox(15);
        controls2.setAlignment(Pos.CENTER_LEFT);
        
        Button btnLoseData = new Button("发送1个并模拟数据丢失");
        Button btnLoseAck = new Button("发送1个并模拟 ACK 丢失");
        btnLoseData.setOnAction(e -> handleBatchSendRequest(1, true, false));
        btnLoseAck.setOnAction(e -> handleBatchSendRequest(1, false, true));
        
        controls2.getChildren().addAll(btnLoseData, btnLoseAck);

        topContainer.getChildren().addAll(controls1, controls2);
        root.setTop(topContainer);

        // 2. 中部动画区域
        // 使用 VBox 来垂直排列 主机动画区 和 底部滑动窗口队列区
        VBox centerContainer = new VBox();
        centerContainer.setStyle("-fx-background-color: white;");
        
        animationPane.setPrefSize(850, 300); // 稍微减小高度，留给底部队列
        drawHost(SENDER_X, "发送方 (Sender)");
        drawHost(RECEIVER_X, "接收方 (Receiver)");
        
        // 初始化底部序号队列和滑动窗口
        initSequenceQueue();
        
        centerContainer.getChildren().addAll(animationPane, queueScrollPane);
        VBox.setVgrow(animationPane, Priority.ALWAYS);
        
        root.setCenter(centerContainer);

        // 3. 底部日志区域
        logArea.setPrefRowCount(10);
        logArea.setEditable(false);
        logArea.setFont(Font.font("Consolas", 13));
        ScrollPane logScrollPane = new ScrollPane(logArea);
        logScrollPane.setFitToWidth(true);
        root.setBottom(logScrollPane);

        log("====== TCP 模拟器启动 ======");
        log("系统初始状态: 窗口大小 = " + simulator.getWindowSize() + ", Base = 0");
    }

    private void initSequenceQueue() {
        queuePane.setPrefHeight(60);
        queuePane.setPadding(new Insets(10, 20, 10, 20)); // 给左右留点边距
        
        // 绘制静态的小方块
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            Rectangle block = new Rectangle(QUEUE_BLOCK_SIZE, QUEUE_BLOCK_SIZE, Color.web("#e0e0e0"));
            block.setStroke(Color.WHITE);
            // 考虑 Padding 偏移
            block.setX(20 + i * (QUEUE_BLOCK_SIZE + QUEUE_SPACING));
            block.setY(10);
            
            Text seqText = new Text(String.valueOf(i));
            seqText.setFont(Font.font("Arial", 10));
            // 居中文字
            seqText.setX(20 + i * (QUEUE_BLOCK_SIZE + QUEUE_SPACING) + (i < 10 ? 11 : 8)); 
            seqText.setY(30);
            
            queuePane.getChildren().addAll(block, seqText);
        }

        // 初始化滑动窗口高亮层
        windowHighlight.setHeight(QUEUE_BLOCK_SIZE + 4);
        windowHighlight.setY(8); // 稍微比方块高一点
        windowHighlight.setFill(Color.rgb(100, 150, 255, 0.4)); // 半透明蓝色
        windowHighlight.setStroke(Color.BLUE);
        windowHighlight.setStrokeWidth(2);
        windowHighlight.setArcWidth(8);
        windowHighlight.setArcHeight(8);
        queuePane.getChildren().add(windowHighlight);
        
        // 配置 ScrollPane
        queueScrollPane.setContent(queuePane);
        queueScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); // 隐藏垂直滚动条
        queueScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); // 隐藏水平滚动条，但允许滚动
        queueScrollPane.setPannable(true); // 允许鼠标拖拽
        queueScrollPane.setPrefHeight(65);
        queueScrollPane.setMinHeight(65);
        queueScrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");
        
        updateWindowUI();
    }

    private void drawHost(int x, String name) {
        // 使用渐变色让主机看起来更现代
        LinearGradient gradient = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#f0f8ff")),
                new Stop(1, Color.web("#b0c4de")));

        Rectangle host = new Rectangle(120, 220, gradient);
        host.setX(x);
        host.setY(40);
        host.setArcWidth(15);
        host.setArcHeight(15);
        host.setStroke(Color.web("#4682b4"));
        host.setStrokeWidth(2);
        
        // 添加阴影效果
        DropShadow shadow = new DropShadow();
        shadow.setOffsetY(3.0);
        shadow.setOffsetX(3.0);
        shadow.setColor(Color.color(0.4, 0.4, 0.4));
        host.setEffect(shadow);

        Text label = new Text(name);
        label.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        label.setFill(Color.web("#333333"));
        label.setX(x + 15);
        label.setY(30);

        animationPane.getChildren().addAll(host, label);
    }

    private void handleBatchSendRequest(int count, boolean loseData, boolean loseAck) {
        List<Integer> seqs = simulator.sendPackets(count);
        
        if (seqs.isEmpty()) {
            log("[警告] 发送失败：当前窗口已满 (Base=" + simulator.getBase() + ")！等待确认...");
            return;
        }

        log("[发送方] 准备发送 " + seqs.size() + " 个数据包: " + seqs);
        
        // 错开飞行：通过小的延迟启动动画，形成排队飞越的效果
        for (int i = 0; i < seqs.size(); i++) {
            int seq = seqs.get(i);
            // 每个包延迟 0.3 秒发出
            final double delaySec = i * 0.3;
            
            // 为了避免覆盖，将 Y 坐标做轻微的交错扰动
            final double yOffset = (seq % 3) * 20; 
            
            Platform.runLater(() -> {
                // 如果是批量发送，只有第一个包应用丢包逻辑（如果设置了的话），防止全部丢失
                boolean currentLoseData = loseData && (seq == seqs.get(0));
                boolean currentLoseAck = loseAck && (seq == seqs.get(0));
                sendPacketAnimation(seq, currentLoseData, currentLoseAck, false, delaySec, TRANSIT_Y_BASE + yOffset);
            });
        }
    }

    private void sendPacketAnimation(int seq, boolean loseData, boolean loseAck, boolean isRetransmit, double delaySec, double yPos) {
        Color color = isRetransmit ? Color.web("#ffd700") : Color.web("#98fb98");
        StackPane packet = createPacketNode("Seq=" + seq, color);
        
        packet.setLayoutX(SENDER_X + 120);
        packet.setLayoutY(yPos);
        animationPane.getChildren().add(packet);

        // 启动超时重传定时器
        startTimer(seq);

        TranslateTransition transition = new TranslateTransition(Duration.seconds(PACKET_SPEED_SEC), packet);
        transition.setInterpolator(Interpolator.EASE_BOTH); // 缓入缓出，丝滑移动
        transition.setDelay(Duration.seconds(delaySec));
        
        if (loseData) {
            transition.setByX((RECEIVER_X - SENDER_X - 120) / 2.0);
            transition.setOnFinished(e -> {
                log("[网络层] 发生丢包！数据包 Seq=" + seq + " 未到达接收方。");
                animationPane.getChildren().remove(packet);
            });
        } else {
            transition.setByX(RECEIVER_X - SENDER_X - 120);
            transition.setOnFinished(e -> {
                animationPane.getChildren().remove(packet);
                processReceiveData(seq, loseAck, yPos + 30); // ACK 在下方一点返回
            });
        }
        transition.play();
    }

    private void processReceiveData(int seq, boolean loseAck, double yPos) {
        int ack = simulator.receivePacket(seq);
        log("[接收方] 收到 Seq=" + seq + ", 期望 Seq=" + ack + "，返回 ACK=" + ack);

        StackPane ackPacket = createPacketNode("ACK=" + ack, Color.web("#ffb6c1"));
        ackPacket.setLayoutX(RECEIVER_X - 60);
        ackPacket.setLayoutY(yPos);
        animationPane.getChildren().add(ackPacket);

        TranslateTransition transition = new TranslateTransition(Duration.seconds(PACKET_SPEED_SEC), ackPacket);
        transition.setInterpolator(Interpolator.EASE_BOTH);

        if (loseAck) {
            transition.setByX(-((RECEIVER_X - SENDER_X - 60) / 2.0));
            transition.setOnFinished(e -> {
                log("[网络层] 发生丢包！ACK=" + ack + " 未到达发送方。");
                animationPane.getChildren().remove(ackPacket);
            });
        } else {
            transition.setByX(-(RECEIVER_X - SENDER_X - 60));
            transition.setOnFinished(e -> {
                animationPane.getChildren().remove(ackPacket);
                processReceiveAck(ack);
            });
        }
        transition.play();
    }

    private void processReceiveAck(int ack) {
        log("[发送方] 收到确认: ACK=" + ack);
        boolean moved = simulator.receiveAck(ack);
        
        if (moved) {
            log("[发送方] ★ 窗口滑动！新的 Base=" + simulator.getBase() + ", 窗口内可用空间: " + (simulator.getWindowSize() - (simulator.getNextSeqNum() - simulator.getBase())));
            updateWindowUI();
        }

        // 取消已经被确认的数据包的定时器
        for (int i = 0; i < ack; i++) {
            if (timers.containsKey(i)) {
                timers.get(i).cancel();
                timers.remove(i);
            }
        }
    }

    private void startTimer(int seq) {
        if (timers.containsKey(seq)) {
            timers.get(seq).cancel();
        }

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    if (seq >= simulator.getBase()) {
                        log("[定时器] ⚠ 数据包 Seq=" + seq + " 超时！触发重传机制。");
                        // 重传时的飞行高度略作调整以示区别，不延迟发出
                        sendPacketAnimation(seq, false, false, true, 0, TRANSIT_Y_BASE - 20);
                    }
                });
            }
        }, 5000); // 设置 5 秒超时，因为动画变长了
        timers.put(seq, timer);
    }

    private void updateWindowUI() {
        Platform.runLater(() -> {
            int base = simulator.getBase();
            int windowSize = simulator.getWindowSize();
            
            // 1. 移动高亮层 (考虑 Padding 偏移 20)
            double targetX = 20 + base * (QUEUE_BLOCK_SIZE + QUEUE_SPACING) - 2;
            TranslateTransition slide = new TranslateTransition(Duration.millis(400), windowHighlight);
            slide.setToX(targetX);
            slide.setInterpolator(Interpolator.EASE_BOTH);
            slide.play();
            
            // 2. 更新宽度
            windowHighlight.setWidth(windowSize * (QUEUE_BLOCK_SIZE + QUEUE_SPACING) + 2);
            
            // 3. 计算 ScrollPane 的平滑滚动
            // 获取整个队列的实际宽度
            double contentWidth = QUEUE_CAPACITY * (QUEUE_BLOCK_SIZE + QUEUE_SPACING) + 40;
            double viewportWidth = queueScrollPane.getViewportBounds().getWidth();
            if (viewportWidth == 0) viewportWidth = 850; // 兜底处理初始化未完成时
            
            // 我们希望高亮窗口的中心点尽量位于视口偏左侧（比如视口宽度的 1/3 处）
            // 计算当前高亮窗口的中心点
            double windowCenterX = targetX + windowHighlight.getWidth() / 2;
            
            // 计算需要的偏移量
            double targetHvalue = (windowCenterX - viewportWidth / 3.0) / (contentWidth - viewportWidth);
            
            // 限制在 0 到 1 之间
            targetHvalue = Math.max(0.0, Math.min(1.0, targetHvalue));
            
            // 使用 Timeline 平滑过渡 hvalue 属性
            Timeline scrollTimeline = new Timeline();
            KeyValue kv = new KeyValue(queueScrollPane.hvalueProperty(), targetHvalue, Interpolator.EASE_BOTH);
            KeyFrame kf = new KeyFrame(Duration.millis(400), kv);
            scrollTimeline.getKeyFrames().add(kf);
            scrollTimeline.play();
        });
    }

    private StackPane createPacketNode(String text, Color color) {
        Rectangle rect = new Rectangle(60, 35, color);
        rect.setArcWidth(10);
        rect.setArcHeight(10);
        rect.setStroke(Color.web("#555555"));
        
        DropShadow shadow = new DropShadow();
        shadow.setRadius(3.0);
        shadow.setOffsetY(2.0);
        shadow.setColor(Color.color(0, 0, 0, 0.3));
        rect.setEffect(shadow);
        
        Text t = new Text(text);
        t.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        t.setFill(Color.web("#222222"));
        
        return new StackPane(rect, t);
    }

    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText(message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
}
