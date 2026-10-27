import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Modern chat window (Java Swing) for the university IT support bot.
 * It contains NO chatbot logic: messages are sent to the FastAPI server through ApiClient.
 *
 * Run:  java ChatUI                              (uses http://127.0.0.1:8000/chat)
 *       java ChatUI http://192.168.1.10:8000/chat
 */
public class ChatUI extends JFrame {

    private static final String DEFAULT_URL = "http://127.0.0.1:8000/chat";

    /** Suggestion chips on the start screen: {label, text sent to the bot}. Edit to match your bot. */
    private static final String[][] QUICK_BUTTONS = {
            {"Wifi", "wifi"}, {"Password", "password"}, {"Email", "email"}, {"Moodle", "moodle"},
            {"Portal", "portal"}, {"Printer", "printer"}, {"Software", "software"}, {"Menu", "menu"}
    };

    // ---------- Colors (warm, minimal palette) ----------
    static final Color BG = new Color(0xFAF9F5);
    static final Color TEXT = new Color(0x1F1E1D);
    static final Color MUTED = new Color(0x8A877D);
    static final Color BORDER = new Color(0xE3E0D6);
    static final Color ACCENT = new Color(0xC96442);
    static final Color ACCENT_HOVER = new Color(0xB5573A);
    static final Color USER_BG = new Color(0xEFECE3);
    static final Color CHIP_BG = Color.WHITE;
    static final Color CHIP_HOVER = new Color(0xF2F0E8);
    static final Color DISABLED_FILL = new Color(0xDDD9CC);
    static final Color DOT_IDLE = new Color(0xCFCBBE);
    static final Color ERROR_BG = new Color(0xFBEAEA);
    static final Color ERROR_TEXT = new Color(0xA32D2D);
    static final Color SELECTION = new Color(0xF1D5C9);

    // ---------- Fonts ----------
    static final String FONT = pickFont("Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Roboto", Font.SANS_SERIF);
    static final String SERIF = pickFont("Georgia", "Times New Roman", Font.SERIF);
    static final Font BODY = new Font(FONT, Font.PLAIN, 15);

    // ---------- Sizes ----------
    static final int MAX_COLUMN = 720;      // width of the centered conversation
    static final int USER_MAX_TEXT = 400;   // max text width inside a user bubble
    static final int BOT_MAX_TEXT = 460;    // max text width of a bot message

    public ChatUI(ApiClient api) {
        super("University IT Support");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(680, 800);
        setMinimumSize(new Dimension(580, 560));
        setLocationRelativeTo(null);
        setIconImage(Avatar.toImage(64));
        setContentPane(new ChatPanel(api));
    }

    static String pickFont(String... names) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) {
            if (available.contains(n)) return n;
        }
        return Font.SANS_SERIF;
    }

    static boolean startsWithArabic(String text) {
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC;
            }
        }
        return false;
    }

    /** Creates a read-only, wrapped, selectable text block that is only as wide as needed. */
    static JTextPane makeText(String text, Color color, int maxWidth) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setBorder(null);
        pane.setSelectionColor(SELECTION);
        pane.setText(text);

        boolean rtl = startsWithArabic(text);
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, BODY.getFamily());
        StyleConstants.setFontSize(attrs, BODY.getSize());
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setLineSpacing(attrs, 0.2f);
        StyleConstants.setAlignment(attrs, rtl ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT);
        StyledDocument doc = pane.getStyledDocument();
        doc.setCharacterAttributes(0, doc.getLength(), attrs, false);
        doc.setParagraphAttributes(0, doc.getLength() + 1, attrs, false);
        if (rtl) pane.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);

        // 1) natural width of the text without wrapping, 2) cap it, 3) ask Swing how tall it is
        pane.setSize(new Dimension(10000, Short.MAX_VALUE));
        int natural = pane.getPreferredSize().width;
        int width = Math.min(maxWidth, natural + 2);
        pane.setSize(new Dimension(width, Short.MAX_VALUE));
        pane.setPreferredSize(new Dimension(width, pane.getPreferredSize().height));
        return pane;
    }

    // =====================================================================
    //  The chat panel (all the interface lives here)
    // =====================================================================

    static class ChatPanel extends JPanel {
        private final ApiClient api;
        private final CardLayout cards = new CardLayout();
        private final JPanel center = new JPanel(cards);
        private final JPanel messagesPanel = new JPanel();
        private final JScrollPane scrollPane;
        private final PlaceholderField input = new PlaceholderField("Describe your problem...");
        private final SendButton sendButton = new SendButton();
        private final RoundedPanel inputCard = new RoundedPanel(Color.WHITE, BORDER, 30);
        private boolean busy = false;
        private int generation = 0;   // changes on "New chat" so old answers are ignored

        ChatPanel(ApiClient api) {
            this.api = api;
            setLayout(new BorderLayout());
            setBackground(BG);

            // Conversation (scrollable, centered column)
            messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
            messagesPanel.setOpaque(false);
            ScrollablePanel wrapper = new ScrollablePanel(new ColumnLayout(MAX_COLUMN));
            wrapper.setBackground(BG);
            wrapper.add(messagesPanel);
            scrollPane = new JScrollPane(wrapper);
            scrollPane.setBorder(null);
            scrollPane.getViewport().setBackground(BG);
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI());
            scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
            scrollPane.getVerticalScrollBar().setBackground(BG);
            scrollPane.getVerticalScrollBar().setUnitIncrement(18);

            center.setBackground(BG);
            center.add(buildWelcome(), "welcome");
            center.add(scrollPane, "chat");

            add(buildTopBar(), BorderLayout.NORTH);
            add(center, BorderLayout.CENTER);
            add(buildBottom(), BorderLayout.SOUTH);

            SwingUtilities.invokeLater(input::requestFocusInWindow);
        }

        // ----- Top bar -----
        private JPanel buildTopBar() {
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(BG);
            bar.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, BORDER), new EmptyBorder(10, 18, 10, 18)));

            JLabel title = new JLabel("IT Support");
            title.setFont(new Font(FONT, Font.BOLD, 16));
            title.setForeground(TEXT);
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            left.setOpaque(false);
            left.add(new Avatar(30));
            left.add(title);

            PillButton newChat = new PillButton("New chat", BG);
            newChat.addActionListener(e -> newChat());

            bar.add(left, BorderLayout.WEST);
            bar.add(newChat, BorderLayout.EAST);
            return bar;
        }

        // ----- Start screen: greeting + suggestion chips -----
        private JPanel buildWelcome() {
            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setOpaque(false);

            Avatar logo = new Avatar(54);
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel greeting = new JLabel("How can I help you today?");
            greeting.setFont(new Font(SERIF, Font.PLAIN, 30));
            greeting.setForeground(TEXT);
            greeting.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel sub = new JLabel("Pick a topic or describe your problem in English, French or Arabic.");
            sub.setFont(new Font(FONT, Font.PLAIN, 14));
            sub.setForeground(MUTED);
            sub.setAlignmentX(Component.CENTER_ALIGNMENT);

            column.add(logo);
            column.add(Box.createVerticalStrut(16));
            column.add(greeting);
            column.add(Box.createVerticalStrut(8));
            column.add(sub);
            column.add(Box.createVerticalStrut(22));

            // chips, 4 per row
            for (int i = 0; i < QUICK_BUTTONS.length; i += 4) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 5));
                row.setOpaque(false);
                row.setAlignmentX(Component.CENTER_ALIGNMENT);
                for (int j = i; j < Math.min(i + 4, QUICK_BUTTONS.length); j++) {
                    String[] item = QUICK_BUTTONS[j];
                    PillButton chip = new PillButton(item[0], CHIP_BG);
                    chip.addActionListener(e -> send(item[1]));
                    row.add(chip);
                }
                column.add(row);
            }

            JPanel welcome = new JPanel(new GridBagLayout());
            welcome.setBackground(BG);
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(0, 0, 70, 0);   // moves the content slightly above the middle
            welcome.add(column, gc);
            return welcome;
        }

        // ----- Bottom: rounded input box -----
        private JPanel buildBottom() {
            inputCard.setLayout(new BorderLayout(8, 0));
            inputCard.setBorder(new EmptyBorder(7, 22, 7, 8));
            inputCard.add(input, BorderLayout.CENTER);

            JPanel sendHolder = new JPanel(new GridBagLayout());
            sendHolder.setOpaque(false);
            sendHolder.add(sendButton);
            inputCard.add(sendHolder, BorderLayout.EAST);

            input.addActionListener(e -> send(input.getText()));          // Enter key
            sendButton.addActionListener(e -> send(input.getText()));
            sendButton.setEnabled(false);
            input.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { refreshSendButton(); }
                public void removeUpdate(DocumentEvent e) { refreshSendButton(); }
                public void changedUpdate(DocumentEvent e) { refreshSendButton(); }
            });
            input.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { inputCard.setBorderColor(new Color(0xBDB8A8)); }
                @Override public void focusLost(FocusEvent e) { inputCard.setBorderColor(BORDER); }
            });

            JLabel hint = new JLabel("For urgent issues, contact the IT office.", SwingConstants.CENTER);
            hint.setFont(new Font(FONT, Font.PLAIN, 12));
            hint.setForeground(MUTED);
            hint.setBorder(new EmptyBorder(8, 0, 0, 0));

            JPanel inner = new JPanel(new BorderLayout());
            inner.setOpaque(false);
            inner.add(inputCard, BorderLayout.CENTER);
            inner.add(hint, BorderLayout.SOUTH);

            JPanel bottom = new JPanel(new ColumnLayout(MAX_COLUMN));
            bottom.setBackground(BG);
            bottom.setBorder(new EmptyBorder(8, 20, 12, 20));
            bottom.add(inner);
            return bottom;
        }

        private void refreshSendButton() {
            sendButton.setEnabled(!busy && !input.getText().trim().isEmpty());
        }

        // ----- Sending -----
        void send(String text) {
            final String message = text.trim();
            if (message.isEmpty() || busy) return;

            input.setText("");
            cards.show(center, "chat");
            addUserRow(message);
            setBusy(true);
            final int gen = generation;
            JPanel typingRow = addBotRow(new TypingDots());

            // The network call runs in a background thread so the window never freezes
            CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return api.chat(message);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    })
                    .whenComplete((reply, error) -> SwingUtilities.invokeLater(() -> {
                        if (gen != generation) return;   // the user started a new chat meanwhile
                        messagesPanel.remove(typingRow);
                        if (error != null) {
                            addErrorRow(friendlyError(error));
                        } else {
                            addBotMessage(reply);
                        }
                        setBusy(false);
                    }));
        }

        private void newChat() {
            generation++;
            messagesPanel.removeAll();
            api.resetSession();
            input.setText("");
            setBusy(false);
            cards.show(center, "welcome");
            revalidate();
            repaint();
            input.requestFocusInWindow();
        }

        private void setBusy(boolean value) {
            busy = value;
            refreshSendButton();
            if (!value) input.requestFocusInWindow();
        }

        private String friendlyError(Throwable error) {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return "Cannot reach the chatbot server:\n" + api.getUrl() + "\nIs the FastAPI server running?";
            }
            if (cause instanceof HttpTimeoutException) {
                return "The server took too long to answer. Please try again.";
            }
            return "Error: " + cause.getMessage();
        }

        // ----- Showing messages -----
        private void addUserRow(String text) {
            RoundedPanel bubble = new RoundedPanel(USER_BG, null, 22);
            bubble.setLayout(new BorderLayout());
            bubble.setBorder(new EmptyBorder(10, 16, 10, 16));
            bubble.add(makeText(text, TEXT, USER_MAX_TEXT), BorderLayout.CENTER);

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(8, 24, 8, 24));
            row.add(bubble, BorderLayout.EAST);
            appendRow(row);
        }

        private void addBotMessage(String text) {
            addBotRow(makeText(text, TEXT, BOT_MAX_TEXT));
        }

        private void addErrorRow(String text) {
            RoundedPanel box = new RoundedPanel(ERROR_BG, null, 16);
            box.setLayout(new BorderLayout());
            box.setBorder(new EmptyBorder(10, 14, 10, 14));
            box.add(makeText(text, ERROR_TEXT, BOT_MAX_TEXT - 30), BorderLayout.CENTER);
            addBotRow(box);
        }

        /** A bot row: small avatar on the left, content on the right (no bubble). */
        private JPanel addBotRow(JComponent content) {
            JPanel avatarHolder = new JPanel(new BorderLayout());
            avatarHolder.setOpaque(false);
            avatarHolder.add(new Avatar(28), BorderLayout.NORTH);

            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.setBorder(new EmptyBorder(3, 0, 0, 0));
            holder.add(content, BorderLayout.WEST);   // WEST keeps the content at its own width

            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(10, 24, 10, 24));
            row.add(avatarHolder, BorderLayout.WEST);
            row.add(holder, BorderLayout.CENTER);
            return appendRow(row);
        }

        private JPanel appendRow(JPanel row) {
            messagesPanel.add(row);
            messagesPanel.revalidate();
            messagesPanel.repaint();
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(this::scrollToBottom));
            return row;
        }

        void scrollToBottom() {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }
    }

    // =====================================================================
    //  Small custom components
    // =====================================================================

    /** Panel with rounded corners, an optional 1px border and a fill color. */
    static class RoundedPanel extends JPanel {
        private final Color fill;
        private Color borderColor;
        private final int arc;

        RoundedPanel(Color fill, Color borderColor, int arc) {
            this.fill = fill;
            this.borderColor = borderColor;
            this.arc = arc;
            setOpaque(false);
        }

        void setBorderColor(Color c) {
            this.borderColor = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, arc, arc));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded "pill" button used for chips and for "New chat". */
    static class PillButton extends JButton {
        private final Color restFill;
        private boolean hover = false;

        PillButton(String text, Color restFill) {
            super(text);
            this.restFill = restFill;
            setFont(new Font(FONT, Font.PLAIN, 14));
            setForeground(TEXT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(getText()) + 34, 38);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hover ? CHIP_HOVER : restFill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
            g2.setColor(BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, getHeight(), getHeight()));
            g2.dispose();
            super.paintComponent(g);   // draws the text
        }
    }

    /** Round send button with an up arrow. */
    static class SendButton extends JButton {
        private boolean hover = false;

        SendButton() {
            setPreferredSize(new Dimension(36, 36));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setToolTipText("Send");
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth(), h = getHeight();
            g2.setColor(isEnabled() ? (hover ? ACCENT_HOVER : ACCENT) : DISABLED_FILL);
            g2.fillOval(0, 0, w, h);

            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = w / 2f;
            g2.drawLine(Math.round(cx), Math.round(h * 0.70f), Math.round(cx), Math.round(h * 0.32f));
            Path2D head = new Path2D.Float();
            head.moveTo(cx - 5.5f, h * 0.32f + 5.5f);
            head.lineTo(cx, h * 0.32f);
            head.lineTo(cx + 5.5f, h * 0.32f + 5.5f);
            g2.draw(head);
            g2.dispose();
        }
    }

    /** Round colored avatar with a small star (also used as the window icon). */
    static class Avatar extends JComponent {
        private final int size;

        Avatar(int size) {
            this.size = size;
            Dimension d = new Dimension(size, size);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            draw(g2, 0, 0, size);
            g2.dispose();
        }

        static void draw(Graphics2D g2, int x, int y, int size) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ACCENT);
            g2.fillOval(x, y, size, size);

            double cx = x + size / 2.0, cy = y + size / 2.0;
            double outer = size * 0.31, inner = size * 0.12;
            Path2D star = new Path2D.Double();
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8 - Math.PI / 2;
                double r = (i % 2 == 0) ? outer : inner;
                double px = cx + Math.cos(angle) * r, py = cy + Math.sin(angle) * r;
                if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
            }
            star.closePath();
            g2.setColor(Color.WHITE);
            g2.fill(star);
        }

        static BufferedImage toImage(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            draw(g2, 0, 0, size);
            g2.dispose();
            return img;
        }
    }

    /** Three animated dots shown while waiting for the server. */
    static class TypingDots extends JComponent {
        private int phase = 0;
        private final Timer timer = new Timer(320, e -> {
            phase = (phase + 1) % 3;
            repaint();
        });

        TypingDots() {
            setPreferredSize(new Dimension(46, 22));
        }

        @Override public void addNotify() { super.addNotify(); timer.start(); }
        @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < 3; i++) {
                g2.setColor(i == phase ? ACCENT : DOT_IDLE);
                g2.fillOval(4 + i * 14, 7, 8, 8);
            }
            g2.dispose();
        }
    }

    /** Text field with a gray hint text when it is empty. */
    static class PlaceholderField extends JTextField {
        private final String hint;

        PlaceholderField(String hint) {
            this.hint = hint;
            setOpaque(false);
            setBorder(null);
            setFont(BODY);
            setForeground(TEXT);
            setCaretColor(TEXT);
            setSelectionColor(SELECTION);
            getDocument().putProperty("i18n", Boolean.TRUE);   // correct display of Arabic / mixed text
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(MUTED);
                FontMetrics fm = g2.getFontMetrics();
                Insets in = getInsets();
                g2.drawString(hint, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        }
    }

    /** Thin, rounded scroll bar without arrow buttons. */
    static class ModernScrollBarUI extends BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }

        private JButton emptyButton() {
            JButton b = new JButton();
            Dimension zero = new Dimension(0, 0);
            b.setPreferredSize(zero);
            b.setMinimumSize(zero);
            b.setMaximumSize(zero);
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xCFCBBE));
            g2.fillRoundRect(r.x + 3, r.y + 2, r.width - 6, r.height - 4, 8, 8);
            g2.dispose();
        }
    }

    /** Puts its single child in the middle, never wider than maxWidth. */
    static class ColumnLayout implements LayoutManager {
        private final int maxWidth;

        ColumnLayout(int maxWidth) { this.maxWidth = maxWidth; }

        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            Dimension d = parent.getComponentCount() == 0 ? new Dimension() : parent.getComponent(0).getPreferredSize();
            return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

        @Override
        public void layoutContainer(Container parent) {
            if (parent.getComponentCount() == 0) return;
            Insets in = parent.getInsets();
            int available = parent.getWidth() - in.left - in.right;
            int w = Math.min(available, maxWidth);
            Component child = parent.getComponent(0);
            child.setBounds(in.left + (available - w) / 2, in.top, w, child.getPreferredSize().height);
        }
    }

    /** A panel that always has the same width as the scroll pane (so everything lines up). */
    static class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel(LayoutManager layout) { super(layout); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // =====================================================================
    //  Program entry point
    // =====================================================================

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");   // smoother text
        System.setProperty("swing.aatext", "true");
        String url = args.length > 0 ? args[0] : DEFAULT_URL;
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) { }
            new ChatUI(new ApiClient(url)).setVisible(true);
        });
    }
}