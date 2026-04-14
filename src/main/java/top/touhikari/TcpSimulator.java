package top.touhikari;

/**
 * TCP 滑动窗口（累积确认）模拟器核心逻辑类。
 * 独立于 UI 界面，仅维护发送和接收的状态及序列号。
 */
public class TcpSimulator {
    // 窗口大小限制
    private final int windowSize = 4;
    
    // 发送方状态
    private int base = 0;           // 最早未被确认的序号
    private int nextSeqNum = 0;     // 下一个要发送的序号
    
    // 接收方状态
    private int expectedSeqNum = 0; // 期望收到的下一个序号（TCP 累积确认机制）

    /**
     * 判断当前窗口是否允许发送新数据包
     * @return 允许发送返回 true，否则返回 false
     */
    public boolean canSend() {
        return nextSeqNum < base + windowSize;
    }

    /**
     * 发送一个新数据包
     * @return 发送数据包的序列号 (Seq)，如果窗口满则返回 -1
     */
    public int sendPacket() {
        if (canSend()) {
            int seq = nextSeqNum;
            nextSeqNum++;
            return seq;
        }
        return -1;
    }

    /**
     * 接收方处理到达的数据包
     * @param seq 到达的数据包序列号
     * @return 返回应当发送的 ACK 确认号
     */
    public int receivePacket(int seq) {
        // TCP 接收方收到期望的数据包，期望序列号 + 1
        if (seq == expectedSeqNum) {
            expectedSeqNum++;
        }
        // 如果失序，TCP 仍然返回预期的序列号 (Duplicate ACK)
        // 实际上在简单实现中这就是 GBN/TCP 累积确认的行为
        return expectedSeqNum;
    }

    /**
     * 发送方处理收到的 ACK
     * @param ack 收到的 ACK 确认号
     * @return 如果引起了窗口滑动则返回 true，否则（重复 ACK 或旧 ACK）返回 false
     */
    public boolean receiveAck(int ack) {
        // TCP 是累积确认：收到 ACK=N 表示序列号小于 N 的所有字节均已正确收到
        if (ack > base) {
            base = ack;
            return true;
        }
        return false;
    }

    public int getBase() {
        return base;
    }

    public int getNextSeqNum() {
        return nextSeqNum;
    }

    public int getExpectedSeqNum() {
        return expectedSeqNum;
    }

    public int getWindowSize() {
        return windowSize;
    }
}
