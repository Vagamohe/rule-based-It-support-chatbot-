import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Chat window (Java Swing) for the university IT support bot.
 * It contains NO chatbot logic: messages are sent to the FastAPI server through ApiClient.
 *
 * Run:  java ChatUI                              (uses DEFAULT_URL)
 *       java ChatUI http://192.168.1.10:8000/chat
 *
 * Look: a deep-lagoon side rail with the help topics, a mist-grey conversation area,
 * and a marigold "signal" mark that stands for the bot (it is also the typing indicator).
 */
public class ChatUI extends JFrame {

    private static final String DEFAULT_URL =
    "https://rule-based-it-support-chatbot-r8s4.onrender.com/";

    /** Topic shortcuts in the side rail: {label, text sent to the bot}. Edit to match your bot. */
    private static final String[][] QUICK_BUTTONS = {
            {"Wifi", "wifi"}, {"Password", "password"}, {"Email", "email"}, {"Moodle", "moodle"},
            {"Portal", "portal"}, {"Printer", "printer"}, {"Software", "software"}, {"Menu", "menu"}
    };

    // ---------- Colors ----------
    static final Color BG = new Color(0xF1F4F2);            // mist
    static final Color SURFACE = Color.WHITE;
    static final Color TEXT = new Color(0x14292B);
    static final Color MUTED = new Color(0x6B7F80);
    static final Color BORDER = new Color(0xD9E2DF);
    static final Color INK = new Color(0x0F3B3D);           // deep lagoon (rail + your messages)
    static final Color INK_HOVER = new Color(0x185153);
    static final Color INK_TEXT = new Color(0xE3F0ED);
    static final Color INK_MUTED = new Color(0x8FB1AD);
    static final Color ACCENT = new Color(0xF2A93B);        // marigold (bot + actions)
    static final Color ACCENT_HOVER = new Color(0xE29722);
    static final Color DISABLED_FILL = new Color(0xDCE4E1);
    static final Color DISABLED_ARROW = new Color(0xA9B9B5);
    static final Color DOT_IDLE = new Color(0xCBD7D3);
    static final Color ERROR_BG = new Color(0xFDECEA);
    static final Color ERROR_TEXT = new Color(0xA32D2D);
    static final Color SELECTION = new Color(0xBFE3DD);

    // ---------- Fonts ----------
    static final String FONT = pickFont("Segoe UI", "SF Pro Text", "Helvetica Neue", "Noto Sans", "Roboto", Font.SANS_SERIF);
    static final String HEAD = pickFont("Bahnschrift", "Avenir Next", "Trebuchet MS", FONT);
    static final Font BODY = new Font(FONT, Font.PLAIN, 15);

    // ---------- Sizes ----------
    static final int RAIL_W = 220;          // width of the side rail
    static final int MAX_COLUMN = 760;      // width of the centered conversation
    static final int USER_MAX_TEXT = 420;   // max text width inside your bubble
    static final int BOT_MAX_TEXT = 520;    // max text width inside a bot bubble

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public ChatUI(ApiClient api) {
        super("University IT Support");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(960, 720);
        setMinimumSize(new Dimension(760, 540));
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
        private final RoundedPanel inputCard = new RoundedPanel(SURFACE, BORDER, 22);
        private boolean busy = false;
        private int generation = 0;   // changes on "New chat" so old answers are ignored

        ChatPanel(ApiClient api) {
            this.api = api;
            setLayout(new BorderLayout());
            setBackground(BG);

            // Conversation (scrollable, centered column)
            messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
            messagesPanel.setOpaque(false);
            messagesPanel.setBorder(new EmptyBorder(14, 0, 14, 0));
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

            JPanel main = new JPanel(new BorderLayout());
            main.setBackground(BG);
            main.add(center, BorderLayout.CENTER);
            main.add(buildBottom(), BorderLayout.SOUTH);

            add(buildRail(), BorderLayout.WEST);
            add(main, BorderLayout.CENTER);

            SwingUtilities.invokeLater(input::requestFocusInWindow);
        }

        // ----- Side rail: brand, topic shortcuts, new chat -----
        private JPanel buildRail() {
            JPanel rail = new JPanel(new BorderLayout());
            rail.setBackground(INK);
            rail.setPreferredSize(new Dimension(RAIL_W, 0));
            rail.setBorder(new EmptyBorder(22, 14, 18, 14));

            JLabel title = new JLabel("IT Support");
            title.setFont(new Font(HEAD, Font.BOLD, 19));
            title.setForeground(Color.WHITE);
            JLabel sub = new JLabel("University help desk");
            sub.setFont(new Font(FONT, Font.PLAIN, 12));
            sub.setForeground(INK_MUTED);
            JPanel names = new JPanel();
            names.setLayout(new BoxLayout(names, BoxLayout.Y_AXIS));
            names.setOpaque(false);
            names.add(title);
            names.add(sub);

            JPanel avatarHolder = new JPanel(new BorderLayout());
            avatarHolder.setOpaque(false);
            avatarHolder.add(new Avatar(40), BorderLayout.NORTH);

            JPanel brand = new JPanel(new BorderLayout(12, 0));
            brand.setOpaque(false);
            brand.setBorder(new EmptyBorder(0, 4, 26, 0));
            brand.add(avatarHolder, BorderLayout.WEST);
            brand.add(names, BorderLayout.CENTER);

            JLabel label = new JLabel("Common topics");
            label.setFont(new Font(FONT, Font.PLAIN, 12));
            label.setForeground(INK_MUTED);
            label.setBorder(new EmptyBorder(0, 8, 8, 0));
            JPanel topics = new JPanel();
            topics.setLayout(new BoxLayout(topics, BoxLayout.Y_AXIS));
            topics.setOpaque(false);
            topics.add(label);
            for (String[] item : QUICK_BUTTONS) {
                RailButton b = new RailButton(item[0], true);
                b.addActionListener(e -> send(item[1]));
                topics.add(b);
                topics.add(Box.createVerticalStrut(2));
            }

            RailButton newChat = new RailButton("New chat", false);
            newChat.addActionListener(e -> newChat());
            JLabel hint = new JLabel("<html><body style='width:150px'>For urgent issues, contact the IT office.</body></html>");
            hint.setFont(new Font(FONT, Font.PLAIN, 12));
            hint.setForeground(INK_MUTED);
            hint.setBorder(new EmptyBorder(0, 6, 0, 0));
            JPanel foot = new JPanel(new BorderLayout(0, 14));
            foot.setOpaque(false);
            foot.add(newChat, BorderLayout.NORTH);
            foot.add(hint, BorderLayout.CENTER);

            rail.add(brand, BorderLayout.NORTH);
            rail.add(topics, BorderLayout.CENTER);
            rail.add(foot, BorderLayout.SOUTH);
            return rail;
        }

        // ----- Start screen -----
        private JPanel buildWelcome() {
            JLabel greeting = new JLabel("Something not working?");
            greeting.setFont(new Font(HEAD, Font.BOLD, 36));
            greeting.setForeground(TEXT);

            JLabel sub = new JLabel("<html><body style='width:380px'>Pick a topic from the list, "
                    + "or describe the problem in English, French or Arabic.</body></html>");
            sub.setFont(new Font(FONT, Font.PLAIN, 15));
            sub.setForeground(MUTED);
            sub.setBorder(new EmptyBorder(10, 2, 0, 0));

            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setOpaque(false);
            column.add(greeting);
            column.add(sub);

            WelcomePanel welcome = new WelcomePanel();
            GridBagConstraints gc = new GridBagConstraints();
            gc.anchor = GridBagConstraints.WEST;
            gc.weightx = 1;
            gc.insets = new Insets(0, 64, 56, 0);   // left margin, and slightly above the middle
            welcome.add(column, gc);
            return welcome;
        }

        // ----- Bottom: rounded input box -----
        private JPanel buildBottom() {
            inputCard.setLayout(new BorderLayout(10, 0));
            inputCard.setBorder(new EmptyBorder(7, 20, 7, 7));
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
                @Override public void focusGained(FocusEvent e) { inputCard.setBorderColor(ACCENT); }
                @Override public void focusLost(FocusEvent e) { inputCard.setBorderColor(BORDER); }
            });

            JPanel bottom = new JPanel(new ColumnLayout(MAX_COLUMN));
            bottom.setBackground(BG);
            bottom.setBorder(new EmptyBorder(8, 24, 20, 24));
            bottom.add(inputCard);
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
            JPanel typingRow = addBotRow(botBubble(new TypingSignal(), SURFACE, BORDER), false);

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
        /** Your message: deep-lagoon bubble on the right, square corner at the bottom right. */
        private void addUserRow(String text) {
            RoundedPanel bubble = new RoundedPanel(INK, null, 22, RoundedPanel.CUT_BOTTOM_RIGHT);
            bubble.setLayout(new BorderLayout());
            bubble.setBorder(new EmptyBorder(11, 17, 11, 17));
            bubble.add(makeText(text, Color.WHITE, USER_MAX_TEXT), BorderLayout.CENTER);

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(7, 28, 7, 28));
            row.add(stamped(bubble, Component.RIGHT_ALIGNMENT), BorderLayout.EAST);
            appendRow(row);
        }

        private void addBotMessage(String text) {
            addBotRow(botBubble(makeText(text, TEXT, BOT_MAX_TEXT), SURFACE, BORDER), true);
        }

        private void addErrorRow(String text) {
            addBotRow(botBubble(makeText(text, ERROR_TEXT, BOT_MAX_TEXT - 30), ERROR_BG, null), false);
        }

        /** White bubble for the bot, square corner at the top left (next to the avatar). */
        private RoundedPanel botBubble(JComponent inner, Color fill, Color border) {
            RoundedPanel bubble = new RoundedPanel(fill, border, 22, RoundedPanel.CUT_TOP_LEFT);
            bubble.setLayout(new BorderLayout());
            bubble.setBorder(new EmptyBorder(11, 17, 11, 17));
            bubble.add(inner, BorderLayout.CENTER);
            return bubble;
        }

        /** Bubble with a small time label underneath. */
        private static JPanel stamped(JComponent bubble, float align) {
            JLabel time = new JLabel(LocalTime.now().format(TIME));
            time.setFont(new Font(FONT, Font.PLAIN, 11));
            time.setForeground(MUTED);
            time.setBorder(new EmptyBorder(4, 4, 0, 4));
            bubble.setAlignmentX(align);
            time.setAlignmentX(align);
            JPanel col = new JPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setOpaque(false);
            col.add(bubble);
            col.add(time);
            return col;
        }

        /** A bot row: marigold avatar tile on the left, bubble on the right. */
        private JPanel addBotRow(JComponent bubble, boolean withTime) {
            JPanel avatarHolder = new JPanel(new BorderLayout());
            avatarHolder.setOpaque(false);
            avatarHolder.add(new Avatar(30), BorderLayout.NORTH);

            JPanel holder = new JPanel(new BorderLayout());
            holder.setOpaque(false);
            holder.add(withTime ? stamped(bubble, Component.LEFT_ALIGNMENT) : bubble, BorderLayout.WEST);

            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(7, 28, 7, 28));
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

    /** Start-screen background: signal rings spreading from a marigold dot in the corner. */
    static class WelcomePanel extends JPanel {
        WelcomePanel() {
            super(new GridBagLayout());
            setBackground(BG);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() - 90, cy = getHeight() - 100;
            int[] alpha = {70, 52, 38, 26, 16};
            g2.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < alpha.length; i++) {
                int r = 70 + i * 80;
                g2.setColor(new Color(INK.getRed(), INK.getGreen(), INK.getBlue(), alpha[i]));
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
            }
            g2.setColor(ACCENT);
            g2.fillOval(cx - 8, cy - 8, 16, 16);
            g2.dispose();
        }
    }

    /** Panel with rounded corners, an optional 1px border, a fill color and an optional squared corner. */
    static class RoundedPanel extends JPanel {
        static final int NONE = 0, CUT_TOP_LEFT = 1, CUT_BOTTOM_RIGHT = 2;
        private final Color fill;
        private Color borderColor;
        private final int arc;
        private final int cut;

        RoundedPanel(Color fill, Color borderColor, int arc) {
            this(fill, borderColor, arc, NONE);
        }

        RoundedPanel(Color fill, Color borderColor, int arc, int cut) {
            this.fill = fill;
            this.borderColor = borderColor;
            this.arc = arc;
            this.cut = cut;
            setOpaque(false);
        }

        void setBorderColor(Color c) {
            this.borderColor = c;
            repaint();
        }

        private Shape shape(float x, float y, float w, float h) {
            Area a = new Area(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
            float c = arc / 2f;
            if (cut == CUT_TOP_LEFT) a.add(new Area(new Rectangle2D.Float(x, y, c, c)));
            if (cut == CUT_BOTTOM_RIGHT) a.add(new Area(new Rectangle2D.Float(x + w - c, y + h - c, c, c)));
            return a;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fill(shape(0, 0, getWidth(), getHeight()));
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.draw(shape(0.5f, 0.5f, getWidth() - 1, getHeight() - 1));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Button for the side rail: a topic row (topic = true) or the "New chat" action. */
    static class RailButton extends JButton {
        private final boolean topic;
        private boolean hover = false;

        RailButton(String text, boolean topic) {
            super(text);
            this.topic = topic;
            int h = topic ? 38 : 42;
            setFont(new Font(FONT, topic ? Font.PLAIN : Font.BOLD, 14));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setPreferredSize(new Dimension(100, h));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
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
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();

            if (topic) {
                if (hover) {
                    g2.setColor(INK_HOVER);
                    g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
                    g2.setColor(ACCENT);
                    g2.fill(new RoundRectangle2D.Float(0, h * 0.28f, 3, h * 0.44f, 3, 3));
                }
                g2.setColor(hover ? Color.WHITE : INK_TEXT);
                g2.drawString(getText(), 18, baseline);
            } else {
                g2.setColor(hover ? ACCENT_HOVER : ACCENT);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));
                g2.setColor(INK);
                g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, baseline);
            }
            g2.dispose();
        }
    }

    /** Rounded-square send button with a right arrow. */
    static class SendButton extends JButton {
        private boolean hover = false;

        SendButton() {
            setPreferredSize(new Dimension(40, 40));
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
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 16, 16));

            g2.setColor(isEnabled() ? INK : DISABLED_ARROW);
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = w / 2f, cy = h / 2f;
            g2.draw(new java.awt.geom.Line2D.Float(cx - 7, cy, cx + 6, cy));
            Path2D head = new Path2D.Float();
            head.moveTo(cx + 0.5f, cy - 6f);
            head.lineTo(cx + 6.5f, cy);
            head.lineTo(cx + 0.5f, cy + 6f);
            g2.draw(head);
            g2.dispose();
        }
    }

    /** The bot's mark: a marigold tile with a dot and two signal arcs (also used as the window icon). */
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
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(ACCENT);
            g2.fill(new RoundRectangle2D.Float(x, y, size, size, size * 0.6f, size * 0.6f));

            g2.setColor(INK);
            float cx = x + size / 2f, cy = y + size * 0.68f;
            float dot = size * 0.09f;
            g2.fill(new Ellipse2D.Float(cx - dot, cy - dot, dot * 2, dot * 2));
            g2.setStroke(new BasicStroke(size * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float[] radii = {size * 0.22f, size * 0.38f};
            for (float r : radii) {
                g2.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 45, 90, Arc2D.OPEN));
            }
        }

        static BufferedImage toImage(int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            draw(g2, 0, 0, size);
            g2.dispose();
            return img;
        }
    }

    /** Signal mark that lights up arc by arc while waiting for the server. */
    static class TypingSignal extends JComponent {
        private int phase = 0;
        private final Timer timer = new Timer(360, e -> {
            phase = (phase + 1) % 3;
            repaint();
        });

        TypingSignal() {
            setPreferredSize(new Dimension(34, 22));
        }

        @Override public void addNotify() { super.addNotify(); timer.start(); }
        @Override public void removeNotify() { timer.stop(); super.removeNotify(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float cx = getWidth() / 2f, cy = 19f;
            g2.setColor(ACCENT);
            g2.fill(new Ellipse2D.Float(cx - 3, cy - 3, 6, 6));
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float[] radii = {8f, 14f};
            for (int i = 0; i < radii.length; i++) {
                g2.setColor(phase >= i + 1 ? ACCENT : DOT_IDLE);
                float r = radii[i];
                g2.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 45, 90, Arc2D.OPEN));
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
            g2.setColor(new Color(0xC5D1CD));
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
