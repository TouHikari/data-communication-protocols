package top.touhikari;

import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class App extends Application {
    private TcpSimulator simulator = new TcpSimulator();
    private Pane animationPane = new Pane();
    private TextArea logArea = new TextArea();
    
    // UI 常量与坐标
    private final int SENDER_X = 50;
    private final int RECEIVER_X = 600;
    private final int TRANSIT_Y = 150;
    private final double PACKET_SPEED_SEC = 1.5;

    // 超时重传定时器，记录已发送未确认的 seq 对应的定时器
    private Map<Integer, Timer> timers = new HashMap<>();
    
    // UI 中的窗口矩形节点（辅助绘制发送窗口）
    private Rectangle windowRect;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();

        // 1. 顶部控制面板
        HBox controls = new HBox(15);
        controls.setPadding(new Insets(10));
        controls.setAlignment(Pos.CENTER);

        Button btnSend = new Button("发送新数据");
        Button btnLoseData = new Button("发送并模拟数据丢失");
        Button btnLoseAck = new Button("发送并模拟 ACK 丢失");

        btnSend.setOnAction(e -> handleSendRequest(false, false));
        btnLoseData.setOnAction(e -> handleSendRequest(true, false));
        btnLoseAck.setOnAction(e -> handleSendRequest(false, true));

        controls.getChildren().addAll(btnSend, btnLoseData, btnLoseAck);
        root.setTop(controls);

        // 2. 中部动画区域
        animationPane.setPrefSize(800, 300);
        drawHost(SENDER_X, "发送方 (Sender)");
        drawHost(RECEIVER_X, "接收方 (Receiver)");
        
        // 绘制初始化滑动窗口指示器
        windowRect = new Rectangle(60, 40, Color.TRANSPARENT);
        windowRect.setStroke(Color.BLUE);
        windowRect.setStrokeWidth(2);
        windowRect.getStrokeDashArray().addAll(5d, 5d);
        animationPane.getChildren().add(windowRect);
        updateWindowUI();
        
        root.setCenter(animationPane);

        // 3. 底部日志区域
        logArea.setPrefRowCount(12);
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;");
        ScrollPane scrollPane = new ScrollPane(logArea);
        scrollPane.setFitToWidth(true);
        root.setBottom(scrollPane);

        Scene scene = new Scene(root, 800, 600);
        primaryStage.setTitle("TCP 可靠数据传输协议可视化模拟器");
        primaryStage.setScene(scene);
        primaryStage.show();

        log("====== TCP 模拟器启动 ======");
        log("系统初始状态: 窗口大小 = " + simulator.getWindowSize() + ", Base = 0");
    }

    private void drawHost(int x, String name) {
        Rectangle host = new Rectangle(100, 200, Color.LIGHTBLUE);
        host.setX(x);
        host.setY(50);
        host.setStroke(Color.DARKGRAY);

        Text label = new Text(name);
        label.setX(x + 10);
        label.setY(40);

        animationPane.getChildren().addAll(host, label);
    }

    private void handleSendRequest(boolean loseData, boolean loseAck) {
        if (!simulator.canSend()) {
            log("[警告] 发送失败：当前窗口已满 (Base=" + simulator.getBase() + ", 窗口大小=" + simulator.getWindowSize() + ")！等待确认...");
            return;
        }

        int seq = simulator.sendPacket();
        log("[发送方] 产生新数据并发送: Seq=" + seq);
        updateWindowUI();
        
        sendPacketAnimation(seq, loseData, loseAck, false);
    }

    private void sendPacketAnimation(int seq, boolean loseData, boolean loseAck, boolean isRetransmit) {
        Color color = isRetransmit ? Color.YELLOW : Color.LIGHTGREEN;
        StackPane packet = createPacketNode("Seq=" + seq, color);
        
        // 数据包从发送方右侧发出
        packet.setLayoutX(SENDER_X + 100);
        packet.setLayoutY(TRANSIT_Y);
        animationPane.getChildren().add(packet);

        // 启动超时重传定时器
        startTimer(seq);

        TranslateTransition transition = new TranslateTransition(Duration.seconds(PACKET_SPEED_SEC), packet);
        
        if (loseData) {
            // 模拟丢包：走到一半停住并消失
            transition.setByX((RECEIVER_X - SENDER_X - 100) / 2.0);
            transition.setOnFinished(e -> {
                log("[网络层] 发生丢包！数据包 Seq=" + seq + " 未到达接收方。");
                animationPane.getChildren().remove(packet);
            });
        } else {
            // 正常到达接收方
            transition.setByX(RECEIVER_X - SENDER_X - 100);
            transition.setOnFinished(e -> {
                animationPane.getChildren().remove(packet);
                processReceiveData(seq, loseAck);
            });
        }
        transition.play();
    }

    private void processReceiveData(int seq, boolean loseAck) {
        int ack = simulator.receivePacket(seq);
        log("[接收方] 收到数据 Seq=" + seq + ", 期望下一个 Seq=" + ack + "，准备返回 ACK=" + ack);

        StackPane ackPacket = createPacketNode("ACK=" + ack, Color.LIGHTCORAL);
        // ACK 从接收方左侧发出，走下半部分路径避免重叠
        ackPacket.setLayoutX(RECEIVER_X - 50);
        ackPacket.setLayoutY(TRANSIT_Y + 40);
        animationPane.getChildren().add(ackPacket);

        TranslateTransition transition = new TranslateTransition(Duration.seconds(PACKET_SPEED_SEC), ackPacket);

        if (loseAck) {
            // 模拟 ACK 丢失：走到一半停住并消失
            transition.setByX(-((RECEIVER_X - SENDER_X - 50) / 2.0));
            transition.setOnFinished(e -> {
                log("[网络层] 发生丢包！ACK=" + ack + " 未到达发送方。");
                animationPane.getChildren().remove(ackPacket);
            });
        } else {
            // 正常到达发送方
            transition.setByX(-(RECEIVER_X - SENDER_X - 50));
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
            log("[发送方] ★ 窗口滑动！新的 Base=" + simulator.getBase() + ", 当前可发送至 Seq=" + (simulator.getBase() + simulator.getWindowSize() - 1));
            updateWindowUI();
        } else {
            log("[发送方] 收到重复的或旧的 ACK=" + ack + "，窗口不移动。");
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
        // 如果之前有定时器，先取消
        if (timers.containsKey(seq)) {
            timers.get(seq).cancel();
        }

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // JavaFX 线程中执行 UI 更新
                Platform.runLater(() -> {
                    // 如果定时器触发时，该序列号仍然没有被确认（seq >= base）
                    if (seq >= simulator.getBase()) {
                        log("[定时器] ⚠ 数据包 Seq=" + seq + " 超时！触发重传机制。");
                        // 重传不带故意丢包的逻辑（为了演示继续进行）
                        sendPacketAnimation(seq, false, false, true);
                    }
                });
            }
        }, 4000); // 设置 4 秒超时
        timers.put(seq, timer);
    }

    private void updateWindowUI() {
        // 简单指示窗口状态，将蓝框放在发送方旁边
        // 为了视觉效果，我们把它放在发送方下部，文本显示 Base 和 NextSeq
        Platform.runLater(() -> {
            windowRect.setX(SENDER_X - 20);
            windowRect.setY(260);
            windowRect.setWidth(140);
            windowRect.setHeight(40);
        });
    }

    private StackPane createPacketNode(String text, Color color) {
        Rectangle rect = new Rectangle(50, 30, color);
        rect.setArcWidth(10);
        rect.setArcHeight(10);
        rect.setStroke(Color.BLACK);
        
        Text t = new Text(text);
        t.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
        
        StackPane pane = new StackPane(rect, t);
        return pane;
    }

    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText(message + "\n");
            // 自动滚动到底部
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
