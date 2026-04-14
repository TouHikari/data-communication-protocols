package top.touhikari;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * 程序的主入口。
 * 负责启动 JavaFX 环境，并组合逻辑类 (TcpSimulator) 与 界面类 (TcpSimulatorUI)。
 */
public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        // 1. 初始化核心逻辑类
        TcpSimulator simulator = new TcpSimulator();

        // 2. 初始化图形界面类，并注入逻辑类
        TcpSimulatorUI ui = new TcpSimulatorUI(simulator);

        // 3. 设置场景和窗口
        Scene scene = new Scene(ui.getRootPane(), 800, 600);
        primaryStage.setTitle("TCP 可靠数据传输协议可视化模拟器");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
