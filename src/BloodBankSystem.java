import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Blood Bank Management System - restyled UI
 * Custom-painted buttons, zebra tables, focus-highlight fields.
 */
public class BloodBankSystem extends JFrame {

    private static final String DB_URL = "jdbc:sqlite:bloodbank.db";
    private static final String[] GROUPS = {"A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
    private static final String FONT = "Segoe UI";

    // Color palette
    private static final Color PRIMARY_RED = new Color(178, 34, 34);
    private static final Color ACCENT_NAVY = new Color(44, 62, 80);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color TEXT_DARK = new Color(33, 37, 41);
    private static final Color BORDER_GRAY = new Color(210, 210, 210);
    private static final Color GREEN = new Color(30, 130, 76);

    private JPanel cardPanel;
    private NavItem[] navItems;
    private JPanel sidebar;
    private CardLayout cardLayout;

    public BloodBankSystem() {
        setTitle("Blood Bank Management System");
        setSize(1180, 760);
        setMinimumSize(new Dimension(1000, 680));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initDatabase();

        cardLayout = new CardLayout();
        cardPanel = new BackgroundPanel(cardLayout);
        JPanel root = new JPanel(new BorderLayout());
        sidebar = buildSidebar();
        root.add(sidebar, BorderLayout.WEST);
        root.add(cardPanel, BorderLayout.CENTER);
        add(root);
        showLanding();
    }

    /* ======================= DATABASE ======================= */

    private Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found: " + e.getMessage());
        }
        return DriverManager.getConnection(DB_URL);
    }

    private void saveDonorAndUpdateStock(String name, int age, String phone, String bloodGroup, String lastDonation, String status) {
        String insertDonorSql = "INSERT INTO donors (name, age, phone, blood_group, last_donation, status) VALUES (?, ?, ?, ?, ?, ?)";
        String updateStockSql = "UPDATE stock SET units = units + 1 WHERE blood_group = ?";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement donorStmt = conn.prepareStatement(insertDonorSql);
                 PreparedStatement stockStmt = conn.prepareStatement(updateStockSql)) {
                donorStmt.setString(1, name);
                donorStmt.setInt(2, age);
                donorStmt.setString(3, phone);
                donorStmt.setString(4, bloodGroup);
                donorStmt.setString(5, lastDonation);
                donorStmt.setString(6, status);
                donorStmt.executeUpdate();

                if ("Available".equalsIgnoreCase(status)) {
                    stockStmt.setString(1, bloodGroup);
                    stockStmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Database Error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean processBloodRequest(String recipientName, String bloodGroup, int unitsNeeded, String hospital, String location) {
        String checkStockSql = "SELECT units FROM stock WHERE blood_group = ?";
        String insertRequestSql = "INSERT INTO requests (name, blood_group, units, hospital, location, status) VALUES (?, ?, ?, ?, ?, ?)";
        String updateStockSql = "UPDATE stock SET units = units - ? WHERE blood_group = ?";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            int currentUnits = 0;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkStockSql)) {
                checkStmt.setString(1, bloodGroup);
                ResultSet rs = checkStmt.executeQuery();
                if (rs.next()) currentUnits = rs.getInt("units");
            }

            boolean enough = currentUnits >= unitsNeeded;
            try (PreparedStatement reqStmt = conn.prepareStatement(insertRequestSql);
                 PreparedStatement stockStmt = conn.prepareStatement(updateStockSql)) {
                reqStmt.setString(1, recipientName);
                reqStmt.setString(2, bloodGroup);
                reqStmt.setInt(3, unitsNeeded);
                reqStmt.setString(4, hospital);
                reqStmt.setString(5, location);
                reqStmt.setString(6, enough ? "Approved" : "Rejected (Insufficient Stock)");
                reqStmt.executeUpdate();

                if (enough) {
                    stockStmt.setInt(1, unitsNeeded);
                    stockStmt.setString(2, bloodGroup);
                    stockStmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }

            if (!enough) {
                JOptionPane.showMessageDialog(this,
                        "Insufficient Stock! Available: " + currentUnits + " units, Requested: " + unitsNeeded,
                        "Request Failed", JOptionPane.ERROR_MESSAGE);
            }
            return enough;
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Database Error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS donors (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, age INTEGER, phone TEXT, blood_group TEXT, " +
                    "last_donation TEXT, status TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS requests (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, blood_group TEXT, units INTEGER, " +
                    "hospital TEXT, location TEXT, status TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS stock (" +
                    "blood_group TEXT PRIMARY KEY, units INTEGER)");

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM stock");
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO stock VALUES(?, ?)")) {
                    for (String bg : GROUPS) {
                        pstmt.setString(1, bg);
                        pstmt.setInt(2, 10);
                        pstmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database Initialization Error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /* ======================= CUSTOM COMPONENTS ======================= */

    /** Rounded, custom-painted button (immune to Look & Feel quirks). */
    private JComponent createCustomButton(String text, Color bg, Color fg, Runnable action) {
        return new JLabel(text, SwingConstants.CENTER) {
            boolean hover = false;
            {
                setOpaque(false);
                setForeground(fg);
                setFont(new Font(FONT, Font.BOLD, 14));
                setBorder(new EmptyBorder(14, 24, 14, 24));
                setCursor(new Cursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) { action.run(); }
                    public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? bg.darker() : bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
    }

    private JComponent button(String text, Runnable action) {
        return createCustomButton(text, PRIMARY_RED, Color.WHITE, action);
    }

    private JComponent secondaryButton(String text, Runnable action) {
        return createCustomButton(text, ACCENT_NAVY, Color.WHITE, action);
    }

    private JComponent back() {
        return secondaryButton("Back to Home", this::showHome);
    }

    /** Blood-drop logo painted with Java2D (no emoji/font dependency). */
    private static class DropIcon extends JComponent {
        DropIcon() { setPreferredSize(new Dimension(56, 72)); setAlignmentX(CENTER_ALIGNMENT); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), d = (int) (w * 0.85);
            Area drop = new Area(new Ellipse2D.Double((w - d) / 2.0, h - d - 2, d, d));
            drop.add(new Area(new Polygon(
                    new int[]{w / 2, (w - d) / 2 + 3, (w + d) / 2 - 3},
                    new int[]{2, h - d / 2 - 2, h - d / 2 - 2}, 3)));
            g2.setColor(PRIMARY_RED);
            g2.fill(drop);
            g2.setColor(new Color(255, 255, 255, 90));
            g2.fillOval(w / 2 - d / 4 - 4, h - d + 4, d / 5, d / 3);
            g2.dispose();
        }
    }

    /** Shared helper: draws a blood-drop silhouette. */
    private static void fillDrop(Graphics2D g2, double cx, double top, double w, double h) {
        int d = (int) (w * 0.85);
        Area drop = new Area(new Ellipse2D.Double(cx - d / 2.0, top + h - d, d, d));
        drop.add(new Area(new Polygon(
                new int[]{(int) cx, (int) (cx - d / 2.0) + 3, (int) (cx + d / 2.0) - 3},
                new int[]{(int) top, (int) (top + h - d / 2.0), (int) (top + h - d / 2.0)}, 3)));
        g2.fill(drop);
    }

    /** Heart silhouette. */
    private static void fillHeart(Graphics2D g2, int x, int y, int s) {
        Area heart = new Area(new Ellipse2D.Double(x, y, s * 0.55, s * 0.55));
        heart.add(new Area(new Ellipse2D.Double(x + s * 0.45, y, s * 0.55, s * 0.55)));
        heart.add(new Area(new Polygon(
                new int[]{x + 2, x + s - 2, x + s / 2},
                new int[]{y + (int) (s * 0.35), y + (int) (s * 0.35), y + s}, 3)));
        g2.fill(heart);
    }

    /** Blood bag illustration with tube and "O+" label. */
    private static void drawBloodBag(Graphics2D g2, int x, int y) {
        int w = 104, h = 140;
        g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(255, 255, 255, 210));
        g2.draw(new QuadCurve2D.Double(x + w / 2, y + h, x + w / 2, y + h + 50, x + w + 36, y + h + 38));
        g2.setStroke(new BasicStroke(1f));

        RoundRectangle2D body = new RoundRectangle2D.Double(x, y, w, h, 26, 26);
        g2.setColor(new Color(255, 255, 255, 238));
        g2.fill(body);
        Shape old = g2.getClip();
        g2.setClip(body);
        g2.setColor(new Color(196, 30, 30));
        g2.fillRect(x, y + 48, w, h - 48);
        g2.setColor(new Color(255, 255, 255, 70));
        g2.fillRect(x + 12, y + 54, 9, h - 66);
        g2.setClip(old);

        g2.setColor(new Color(205, 60, 60));
        g2.fillOval(x + w / 2 - 8, y + 10, 16, 16);
        g2.setColor(Color.WHITE);
        g2.fillRoundRect(x + 20, y + 62, w - 40, 46, 10, 10);
        g2.setColor(PRIMARY_RED);
        g2.setFont(new Font(FONT, Font.BOLD, 22));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString("O+", x + (w - fm.stringWidth("O+")) / 2, y + 94);
    }

    /** Faint heartbeat (ECG) line. */
    private static void drawEcg(Graphics2D g2, int x0, int yb, int x1) {
        g2.setColor(new Color(255, 255, 255, 85));
        g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int[][] pat = {{0, 0}, {40, 0}, {50, -10}, {60, 12}, {72, -34}, {84, 22}, {94, 0}, {140, 0}};
        Path2D.Double path = new Path2D.Double();
        path.moveTo(x0, yb);
        for (int x = x0; x < x1; x += 140)
            for (int[] pt : pat) path.lineTo(Math.min(x + pt[0], x1), yb + pt[1]);
        g2.draw(path);
        g2.setStroke(new BasicStroke(1f));
    }

    /** App-wide background: soft gradient with faint circles and blood drops. */
    private static class BackgroundPanel extends JPanel {
        BackgroundPanel(LayoutManager lm) { super(lm); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 248, 248), w, h, new Color(245, 210, 210)));
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(178, 34, 34, 18));
            g2.fillOval(-120, h - 260, 420, 420);
            g2.fillOval(w - 300, -140, 460, 460);
            g2.setColor(new Color(178, 34, 34, 16));
            fillDrop(g2, w * 0.08, h * 0.20, 70, 90);
            fillDrop(g2, w * 0.92, h * 0.60, 100, 130);
            fillDrop(g2, w * 0.80, h * 0.12, 40, 52);
            fillDrop(g2, w * 0.20, h * 0.76, 50, 65);
            g2.dispose();
        }
    }

    /** White rounded card with a soft drop shadow. */
    private static class CardPanel extends JPanel {
        CardPanel(LayoutManager lm) { super(lm); setOpaque(false); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            for (int i = 1; i <= 8; i++) {
                g2.setColor(new Color(90, 20, 20, 8));
                g2.fillRoundRect(8 - i, 8 - i + 2, w - 16 + 2 * i, h - 16 + 2 * i, 18 + i, 18 + i);
            }
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(8, 8, w - 16, h - 16, 18, 18);
            g2.setColor(new Color(238, 226, 226));
            g2.drawRoundRect(8, 8, w - 17, h - 17, 18, 18);
            g2.dispose();
        }
    }

    /* ======================= UI HELPERS ======================= */

    private void setForm(String title, JPanel contentPanel) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);

        JPanel headerPanel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, PRIMARY_RED.darker(), getWidth(), 0, new Color(222, 62, 62)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        headerPanel.setOpaque(false);
        headerPanel.setBorder(new EmptyBorder(18, 25, 18, 25));
        JLabel header = new JLabel(title);
        header.setFont(new Font(FONT, Font.BOLD, 22));
        header.setForeground(Color.WHITE);
        headerPanel.add(header, BorderLayout.WEST);
        wrapper.add(headerPanel, BorderLayout.NORTH);

        JPanel margin = new JPanel(new BorderLayout());
        margin.setOpaque(false);
        margin.setBorder(new EmptyBorder(25, 25, 25, 25));
        margin.add(contentPanel, BorderLayout.CENTER);
        wrapper.add(margin, BorderLayout.CENTER);

        cardPanel.removeAll(); // avoid piling up old screens
        cardPanel.add(wrapper, title);
        cardLayout.show(cardPanel, title);
        if (sidebar != null) sidebar.setVisible(true);
        highlightNav(title);
        cardPanel.revalidate();
        cardPanel.repaint();
    }

    private JTextField createStyledTextField() {
        JTextField field = new JTextField(18);
        styleInput(field);
        return field;
    }

    private JPasswordField createStyledPasswordField() {
        JPasswordField field = new JPasswordField(18);
        styleInput(field);
        return field;
    }

    private void styleInput(JTextField field) {
        field.setFont(new Font(FONT, Font.PLAIN, 14));
        Border normal = BorderFactory.createCompoundBorder(new LineBorder(BORDER_GRAY, 1), new EmptyBorder(8, 10, 8, 10));
        Border focused = BorderFactory.createCompoundBorder(new LineBorder(PRIMARY_RED, 2), new EmptyBorder(7, 9, 7, 9));
        field.setBorder(normal);
        field.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) { field.setBorder(focused); }
            public void focusLost(FocusEvent e) { field.setBorder(normal); }
        });
    }

    private JComboBox<String> createGroupCombo() {
        JComboBox<String> combo = new JComboBox<>(GROUPS);
        combo.setFont(new Font(FONT, Font.PLAIN, 14));
        combo.setBackground(Color.WHITE);
        return combo;
    }

    private JLabel createStyledLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(FONT, Font.BOLD, 13));
        label.setForeground(TEXT_DARK);
        return label;
    }

    private JPanel formCard(int v, int h) {
        JPanel card = new CardPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(v + 8, h + 8, v + 8, h + 8));
        return card;
    }

    private void addRow(JPanel card, int row, String label, JComponent field) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        card.add(createStyledLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        card.add(field, gbc);
    }

    private void addButtons(JPanel card, int row, JComponent left, JComponent right) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 10, 0, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = row;
        gbc.gridx = 0;
        card.add(left, gbc);
        gbc.gridx = 1;
        card.add(right, gbc);
    }

    private JPanel centered(JComponent c) {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setOpaque(false);
        outer.add(c);
        return outer;
    }

    private DefaultTableModel readOnlyModel(String[] cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private void styleTable(JTable table) {
        table.setRowHeight(34);
        table.setFont(new Font(FONT, Font.PLAIN, 13));
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setGridColor(new Color(235, 235, 235));
        table.setSelectionBackground(new Color(255, 228, 228));
        table.setSelectionForeground(TEXT_DARK);
        table.setFillsViewportHeight(true);

        DefaultTableCellRenderer cells = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                setHorizontalAlignment(CENTER);
                if (!sel) setBackground(row % 2 == 0 ? Color.WHITE : new Color(250, 246, 246));
                setForeground(TEXT_DARK);
                setFont(t.getFont());
                String s = v == null ? "" : v.toString();
                boolean lowStock = v instanceof Integer && "Available Units".equals(t.getColumnName(col)) && (Integer) v < 5;
                if (s.equals("Available") || s.startsWith("Approved")) {
                    setForeground(GREEN);
                    setFont(t.getFont().deriveFont(Font.BOLD));
                } else if (s.equals("Unavailable") || s.startsWith("Rejected") || lowStock) {
                    setForeground(PRIMARY_RED);
                    setFont(t.getFont().deriveFont(Font.BOLD));
                }
                return this;
            }
        };
        for (int i = 0; i < table.getColumnCount(); i++)
            table.getColumnModel().getColumn(i).setCellRenderer(cells);

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(0, 40));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, s, f, r, c);
                l.setBackground(ACCENT_NAVY);
                l.setForeground(Color.WHITE);
                l.setFont(new Font(FONT, Font.BOLD, 13));
                l.setHorizontalAlignment(CENTER);
                l.setOpaque(true);
                l.setBorder(BorderFactory.createEmptyBorder());
                return l;
            }
        });
    }

    /** Standard table screen: optional north component, table in the middle, back button at the bottom. */
    private JPanel tablePage(JComponent north, JTable table, JComponent backBtn) {
        JPanel p = new JPanel(new BorderLayout(15, 15));
        p.setOpaque(false);
        if (north != null) p.add(north, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(new LineBorder(BORDER_GRAY, 1));
        scroll.getViewport().setBackground(Color.WHITE);
        p.add(scroll, BorderLayout.CENTER);

        JPanel bot = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bot.setOpaque(false);
        bot.add(backBtn);
        p.add(bot, BorderLayout.SOUTH);
        return p;
    }

    /* ======================= SIDEBAR & DASHBOARD PARTS ======================= */

    private static class NavItem extends JLabel {
        boolean active, hover;

        NavItem(String text, Runnable action) {
            super(text);
            setFont(new Font(FONT, Font.PLAIN, 15));
            setForeground(new Color(215, 222, 230));
            setBorder(new EmptyBorder(14, 30, 14, 10));
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setOpaque(false);
            setAlignmentX(0f);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { action.run(); }
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        void setActive(boolean a) {
            active = a;
            setFont(new Font(FONT, a ? Font.BOLD : Font.PLAIN, 15));
            setForeground(a ? Color.WHITE : new Color(215, 222, 230));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (active || hover) {
                g.setColor(active ? new Color(178, 34, 34, 230) : new Color(255, 255, 255, 22));
                g.fillRect(0, 0, getWidth(), getHeight());
                if (active) {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, 5, getHeight());
                }
            }
            super.paintComponent(g);
        }
    }

    /** Dashboard tile showing one blood group and its stock. */
    private static class BloodGroupCard extends JComponent {
        final String group;
        final int units;

        BloodGroupCard(String group, int units) {
            this.group = group;
            this.units = units;
            setPreferredSize(new Dimension(150, 100));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g2 = (Graphics2D) g0.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean low = units < 5;
            for (int i = 1; i <= 4; i++) {
                g2.setColor(new Color(90, 20, 20, 9));
                g2.fillRoundRect(4 - i, 4 - i + 2, w - 8 + 2 * i, h - 8 + 2 * i, 16 + i, 16 + i);
            }
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(4, 4, w - 8, h - 8, 16, 16);
            g2.setClip(new RoundRectangle2D.Float(4, 4, w - 8, h - 8, 16, 16));
            g2.setColor(low ? PRIMARY_RED : ACCENT_NAVY);
            g2.fillRect(4, 4, 6, h - 8);
            g2.setClip(null);

            g2.setFont(new Font(FONT, Font.BOLD, 24));
            g2.setColor(TEXT_DARK);
            g2.drawString(group, 26, 42);
            int tw = g2.getFontMetrics().stringWidth(group);
            g2.setColor(PRIMARY_RED);
            fillDrop(g2, 26 + tw + 14, 22, 14, 20);

            g2.setFont(new Font(FONT, Font.BOLD, 28));
            g2.setColor(low ? PRIMARY_RED : TEXT_DARK);
            String u = String.valueOf(units);
            g2.drawString(u, 26, h - 22);
            int uw = g2.getFontMetrics().stringWidth(u);
            g2.setFont(new Font(FONT, Font.PLAIN, 12));
            g2.setColor(Color.GRAY);
            g2.drawString("units", 26 + uw + 6, h - 22);

            if (low) {
                g2.setColor(PRIMARY_RED);
                g2.fillRoundRect(w - 56, 16, 40, 18, 9, 9);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(FONT, Font.BOLD, 10));
                g2.drawString("LOW", w - 46, 29);
            }
            g2.dispose();
        }
    }

    private JPanel buildSidebar() {
        JPanel side = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(28, 38, 50), 0, getHeight(), new Color(46, 64, 82)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(230, 0));

        JPanel logo = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        logo.setOpaque(false);
        logo.setBorder(new EmptyBorder(26, 20, 26, 10));
        logo.setAlignmentX(0f);
        logo.setCursor(new Cursor(Cursor.HAND_CURSOR));
        logo.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showLanding(); }
        });
        logo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        DropIcon drop = new DropIcon();
        drop.setPreferredSize(new Dimension(30, 40));
        JLabel name = new JLabel("<html>Blood<font color='#ff6b6b'>Bank</font></html>");
        name.setFont(new Font(FONT, Font.BOLD, 22));
        name.setForeground(Color.WHITE);
        logo.add(drop);
        logo.add(name);
        side.add(logo);

        String[] names = {"Home Page", "Dashboard", "Become a Donor", "Find Donor", "Request Blood", "Blood Availability", "Admin Panel"};
        Runnable[] actions = {this::showLanding, this::showHome, this::registerDonorForm, this::findDonorForm,
                this::bloodRequestForm, this::showAvailability, this::adminLoginForm};
        navItems = new NavItem[names.length];
        for (int i = 0; i < names.length; i++) {
            navItems[i] = new NavItem(names[i], actions[i]);
            side.add(navItems[i]);
        }

        side.add(Box.createVerticalGlue());
        JLabel foot = new JLabel("Every drop counts");
        foot.setFont(new Font(FONT, Font.ITALIC, 12));
        foot.setForeground(new Color(150, 165, 180));
        foot.setBorder(new EmptyBorder(10, 30, 24, 10));
        foot.setAlignmentX(0f);
        side.add(foot);
        return side;
    }

    private void highlightNav(String title) {
        if (navItems == null) return;
        int idx;
        if (title.equals("Landing")) idx = 0;
        else if (title.startsWith("Blood Bank Dashboard")) idx = 1;
        else if (title.startsWith("Donor Registration")) idx = 2;
        else if (title.startsWith("Search Available")) idx = 3;
        else if (title.equals("Request Blood")) idx = 4;
        else if (title.startsWith("Current Blood")) idx = 5;
        else idx = 6;
        for (int i = 0; i < navItems.length; i++) navItems[i].setActive(i == idx);
    }

    private int scalar(String sql) {
        try (Connection c = getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private JPanel statsRow() {
        JPanel row = new JPanel(new GridLayout(1, 4, 15, 0));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        row.add(createMetricCard("Registered Donors", String.valueOf(scalar("SELECT COUNT(*) FROM donors")), ACCENT_NAVY));
        row.add(createMetricCard("Available Donors", String.valueOf(scalar("SELECT COUNT(*) FROM donors WHERE status='Available'")), GREEN));
        row.add(createMetricCard("Total Requests", String.valueOf(scalar("SELECT COUNT(*) FROM requests")), PRIMARY_RED));
        row.add(createMetricCard("Units In Stock", String.valueOf(scalar("SELECT SUM(units) FROM stock")), new Color(128, 0, 128)));
        return row;
    }

    /* ======================= HOME (LANDING) PAGE ======================= */

    private JLabel linkLabel(String text, Runnable action) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(FONT, Font.BOLD, 14));
        l.setForeground(ACCENT_NAVY);
        l.setBorder(new EmptyBorder(8, 14, 8, 14));
        l.setCursor(new Cursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { action.run(); }
            public void mouseEntered(MouseEvent e) { l.setForeground(PRIMARY_RED); }
            public void mouseExited(MouseEvent e) { l.setForeground(ACCENT_NAVY); }
        });
        return l;
    }

    private void showLanding() {
        sidebar.setVisible(false);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);

        // Top navigation bar
        JPanel nav = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(235, 225, 225));
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        nav.setOpaque(false);
        nav.setBorder(new EmptyBorder(10, 28, 10, 18));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        DropIcon smallDrop = new DropIcon();
        smallDrop.setPreferredSize(new Dimension(24, 32));
        JLabel brandName = new JLabel("<html>Blood<font color='#b22222'>Bank</font></html>");
        brandName.setFont(new Font(FONT, Font.BOLD, 20));
        brandName.setForeground(ACCENT_NAVY);
        brand.add(smallDrop);
        brand.add(brandName);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        links.setOpaque(false);
        links.add(linkLabel("Dashboard", this::showHome));
        links.add(linkLabel("Donate", this::registerDonorForm));
        links.add(linkLabel("Find Donor", this::findDonorForm));
        links.add(linkLabel("Request Blood", this::bloodRequestForm));
        links.add(linkLabel("Admin", this::adminLoginForm));
        nav.add(brand, BorderLayout.WEST);
        nav.add(links, BorderLayout.EAST);
        page.add(nav, BorderLayout.NORTH);

        // Hero
        JPanel hero = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setPaint(new GradientPaint(0, 0, PRIMARY_RED.darker(), w, h, new Color(225, 70, 70)));
                g2.fillRoundRect(0, 0, w, h, 26, 26);
                g2.setColor(new Color(255, 255, 255, 28));
                fillDrop(g2, w - 60, h - 150, 70, 100);
                g2.setColor(new Color(255, 255, 255, 235));
                double cx = w - 190, top = Math.max(18, (h - 200) / 2.0);
                fillDrop(g2, cx, top, 150, 200);
                int cy = (int) (top + 200 - 150 * 0.85 / 2), c = (int) cx;
                g2.setColor(PRIMARY_RED);
                g2.fillRoundRect(c - 8, cy - 30, 16, 60, 6, 6);
                g2.fillRoundRect(c - 30, cy - 8, 60, 16, 6, 6);
                drawBloodBag(g2, w - 450, (int) top + 36);
                g2.setColor(new Color(255, 255, 255, 120));
                fillHeart(g2, w - 92, (int) top + 6, 48);
                drawEcg(g2, 44, h - 36, w - 44);
                g2.dispose();
            }
        };
        hero.setOpaque(false);
        hero.setBorder(new EmptyBorder(30, 44, 30, 44));

        JPanel txt = new JPanel();
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        txt.setOpaque(false);
        JLabel tag = new JLabel("BLOOD BANK MANAGEMENT SYSTEM");
        tag.setFont(new Font(FONT, Font.BOLD, 13));
        tag.setForeground(new Color(255, 215, 215));
        JLabel head = new JLabel("DONATE BLOOD!!!");
        head.setFont(new Font(FONT, Font.BOLD, 46));
        head.setForeground(Color.WHITE);
        JLabel sub = new JLabel("<html><div style='width:360px'>Your single donation can bring a second chance at life. Register in a minute, or request blood when someone needs it.</div></html>");
        sub.setFont(new Font(FONT, Font.PLAIN, 15));
        sub.setForeground(new Color(255, 235, 235));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btns.setOpaque(false);
        btns.add(createCustomButton("Get Started", Color.WHITE, PRIMARY_RED, this::registerDonorForm));
        btns.add(Box.createHorizontalStrut(14));
        btns.add(createCustomButton("Request Blood", ACCENT_NAVY, Color.WHITE, this::bloodRequestForm));
        for (JComponent c : new JComponent[]{tag, head, sub, btns}) c.setAlignmentX(0f);
        txt.add(Box.createVerticalGlue());
        txt.add(tag);
        txt.add(Box.createVerticalStrut(8));
        txt.add(head);
        txt.add(Box.createVerticalStrut(10));
        txt.add(sub);
        txt.add(Box.createVerticalStrut(24));
        txt.add(btns);
        txt.add(Box.createVerticalGlue());
        hero.add(txt, BorderLayout.WEST);

        JLabel live = new JLabel(scalar("SELECT COUNT(*) FROM donors") + " registered donors   \u2022   "
                + scalar("SELECT SUM(units) FROM stock") + " units in stock", SwingConstants.CENTER);
        live.setFont(new Font(FONT, Font.BOLD, 13));
        live.setForeground(ACCENT_NAVY);

        JPanel body = new JPanel(new BorderLayout(0, 20));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(24, 28, 14, 28));
        body.add(hero, BorderLayout.CENTER);
        body.add(live, BorderLayout.SOUTH);
        page.add(body, BorderLayout.CENTER);

        cardPanel.removeAll();
        cardPanel.add(page, "Landing");
        cardLayout.show(cardPanel, "Landing");
        cardPanel.revalidate();
        cardPanel.repaint();
        highlightNav("Landing");
    }

    /* ======================= SCREENS ======================= */

    private void showHome() {
        JPanel page = new JPanel(new BorderLayout(0, 18));
        page.setOpaque(false);

        JPanel hero = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, PRIMARY_RED.darker(), getWidth(), 0, new Color(225, 70, 70)));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(new Color(255, 255, 255, 34));
                fillDrop(g2, getWidth() - 90, 8, 70, getHeight() - 16);
                fillDrop(g2, getWidth() - 190, 32, 36, getHeight() - 42);
                g2.dispose();
            }
        };
        hero.setOpaque(false);
        hero.setBorder(new EmptyBorder(18, 28, 18, 28));
        hero.setAlignmentX(0f);
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        JPanel txt = new JPanel();
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        txt.setOpaque(false);
        JLabel h1 = new JLabel("Every Drop Counts");
        h1.setFont(new Font(FONT, Font.BOLD, 26));
        h1.setForeground(Color.WHITE);
        JLabel h2 = new JLabel("Register as a donor, find a match, or request blood in seconds.");
        h2.setFont(new Font(FONT, Font.PLAIN, 14));
        h2.setForeground(new Color(255, 230, 230));
        txt.add(h1);
        txt.add(Box.createVerticalStrut(6));
        txt.add(h2);
        hero.add(txt, BorderLayout.WEST);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(hero);
        top.add(Box.createVerticalStrut(16));
        top.add(statsRow());
        page.add(top, BorderLayout.NORTH);

        java.util.HashMap<String, Integer> stock = new java.util.HashMap<>();
        try (Connection c = getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM stock")) {
            while (rs.next()) stock.put(rs.getString("blood_group"), rs.getInt("units"));
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, "Error fetching stock: " + ex.getMessage());
        }
        JPanel grid = new JPanel(new GridLayout(2, 4, 14, 14));
        grid.setOpaque(false);
        for (String gname : GROUPS) grid.add(new BloodGroupCard(gname, stock.getOrDefault(gname, 0)));

        JLabel stockTitle = new JLabel("Blood Stock Overview");
        stockTitle.setFont(new Font(FONT, Font.BOLD, 17));
        stockTitle.setForeground(ACCENT_NAVY);
        JPanel mid = new JPanel(new BorderLayout(0, 10));
        mid.setOpaque(false);
        mid.add(stockTitle, BorderLayout.NORTH);
        mid.add(grid, BorderLayout.CENTER);
        page.add(mid, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(1, 3, 14, 0));
        actions.setOpaque(false);
        actions.add(button("Become a Donor", this::registerDonorForm));
        actions.add(button("Find Donor", this::findDonorForm));
        actions.add(button("Request Blood", this::bloodRequestForm));
        page.add(actions, BorderLayout.SOUTH);

        setForm("Blood Bank Dashboard", page);
    }

    private void registerDonorForm() {
        JPanel card = formCard(30, 40);
        JTextField name = createStyledTextField(), age = createStyledTextField(),
                phone = createStyledTextField(), lastDonation = createStyledTextField();
        JComboBox<String> group = createGroupCombo();

        addRow(card, 0, "Full Name:", name);
        addRow(card, 1, "Age (18-65):", age);
        addRow(card, 2, "Phone Number:", phone);
        addRow(card, 3, "Blood Group:", group);
        addRow(card, 4, "Last Donation (YYYY-MM-DD):", lastDonation);

        JComponent register = button("Register", () -> {
            String nameTxt = name.getText().trim(), ageTxt = age.getText().trim(), phoneTxt = phone.getText().trim();
            String lastDonTxt = lastDonation.getText().trim();

            if (nameTxt.isEmpty() || ageTxt.isEmpty() || phoneTxt.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please fill in all required fields.");
                return;
            }
            try {
                int parsedAge = Integer.parseInt(ageTxt);
                if (parsedAge < 18 || parsedAge > 65) {
                    JOptionPane.showMessageDialog(this, "Age must be between 18 and 65 to register as a donor.");
                    return;
                }

                String status = "Available";
                if (!lastDonTxt.isEmpty()) {
                    try {
                        LocalDate lastDate = LocalDate.parse(lastDonTxt);
                        if (lastDate.isAfter(LocalDate.now())) {
                            JOptionPane.showMessageDialog(this, "Last donation date cannot be in the future.");
                            return;
                        }
                        if (ChronoUnit.DAYS.between(lastDate, LocalDate.now()) < 90) status = "Unavailable";
                    } catch (DateTimeParseException ex) {
                        JOptionPane.showMessageDialog(this, "Invalid date format. Use YYYY-MM-DD.");
                        return;
                    }
                }

                saveDonorAndUpdateStock(nameTxt, parsedAge, phoneTxt, group.getSelectedItem().toString(),
                        lastDonTxt.isEmpty() ? "None" : lastDonTxt, status);

                JOptionPane.showMessageDialog(this, "Registration Successful!\nDonor Status: " + status +
                        ("Available".equals(status) ? "\nStock Updated (+1 Unit)" : ""));
                showHome();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Age must be a valid number.");
            }
        });

        addButtons(card, 5, register, back());
        setForm("Donor Registration", centered(card));
    }

    private void findDonorForm() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 12));
        top.setBackground(CARD_BG);
        top.setBorder(new LineBorder(BORDER_GRAY, 1));

        JComboBox<String> groupSelect = createGroupCombo();
        top.add(createStyledLabel("Select Blood Group Needed:"));
        top.add(groupSelect);

        String[] cols = {"ID", "Name", "Age", "Phone", "Blood Group", "Last Donation", "Status"};
        DefaultTableModel model = readOnlyModel(cols);
        JTable table = new JTable(model);
        styleTable(table);

        top.add(button("Search", () -> {
            model.setRowCount(0);
            String sql = "SELECT * FROM donors WHERE blood_group = ? AND status = 'Available'";
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, groupSelect.getSelectedItem().toString());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    model.addRow(new Object[]{rs.getInt("id"), rs.getString("name"), rs.getInt("age"),
                            rs.getString("phone"), rs.getString("blood_group"),
                            rs.getString("last_donation"), rs.getString("status")});
                }
                if (model.getRowCount() == 0)
                    JOptionPane.showMessageDialog(this, "No available donors found for this blood group.");
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Search Error: " + ex.getMessage());
            }
        }));

        setForm("Search Available Donors", tablePage(top, table, back()));
    }

    private void bloodRequestForm() {
        JPanel card = formCard(30, 40);
        JTextField name = createStyledTextField(), units = createStyledTextField(),
                hospital = createStyledTextField(), loc = createStyledTextField();
        JComboBox<String> group = createGroupCombo();

        addRow(card, 0, "Patient Name:", name);
        addRow(card, 1, "Blood Group:", group);
        addRow(card, 2, "Units Required:", units);
        addRow(card, 3, "Hospital:", hospital);
        addRow(card, 4, "Location:", loc);

        JComponent submit = button("Submit Request", () -> {
            String nameTxt = name.getText().trim(), unitsTxt = units.getText().trim();
            if (nameTxt.isEmpty() || unitsTxt.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter Patient Name and Units Required.");
                return;
            }
            try {
                int unitsNeeded = Integer.parseInt(unitsTxt);
                if (unitsNeeded <= 0) {
                    JOptionPane.showMessageDialog(this, "Units required must be greater than 0.");
                    return;
                }
                if (processBloodRequest(nameTxt, group.getSelectedItem().toString(), unitsNeeded,
                        hospital.getText().trim(), loc.getText().trim())) {
                    JOptionPane.showMessageDialog(this,
                            "SUCCESS! Blood request automatically APPROVED.\n" + unitsNeeded + " unit(s) allocated from inventory.",
                            "Request Approved", JOptionPane.INFORMATION_MESSAGE);
                    showHome();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Units must be a valid number.");
            }
        });

        addButtons(card, 5, submit, back());
        setForm("Request Blood", centered(card));
    }

    private void showAvailability() {
        DefaultTableModel model = readOnlyModel(new String[]{"Blood Group", "Available Units"});
        JTable table = new JTable(model);
        styleTable(table);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM stock")) {
            while (rs.next()) model.addRow(new Object[]{rs.getString("blood_group"), rs.getInt("units")});
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, "Error fetching stock: " + ex.getMessage());
        }
        JLabel note = new JLabel("Units shown in red are running low (fewer than 5).", SwingConstants.CENTER);
        note.setFont(new Font(FONT, Font.ITALIC, 12));
        note.setForeground(Color.GRAY);

        setForm("Current Blood Availability", tablePage(note, table, back()));
    }

    private void adminLoginForm() {
        JPanel card = formCard(40, 50);
        JTextField user = createStyledTextField();
        JPasswordField pass = createStyledPasswordField();

        addRow(card, 0, "Admin Username:", user);
        addRow(card, 1, "Password:", pass);

        JComponent login = button("Login", () -> {
            if (user.getText().equals("admin") && new String(pass.getPassword()).equals("admin123")) {
                showAdminMenu();
            } else {
                JOptionPane.showMessageDialog(this, "Invalid Credentials", "Access Denied", JOptionPane.ERROR_MESSAGE);
            }
        });

        addButtons(card, 2, login, back());
        setForm("Admin Login", centered(card));
    }

    private void showAdminMenu() {
        JPanel page = new JPanel(new BorderLayout(0, 22));
        page.setOpaque(false);
        page.add(statsRow(), BorderLayout.NORTH);

        JPanel card = new CardPanel(new GridLayout(2, 2, 20, 20));
        card.setBorder(new EmptyBorder(48, 48, 48, 48));
        card.add(button("View Registered Donors", this::viewDonors));
        card.add(button("Request Summary Report", this::showRequestSummary));
        card.add(button("Add Blood Stock", this::addStockForm));
        card.add(secondaryButton("Logout", this::showHome));
        page.add(centered(card), BorderLayout.CENTER);

        setForm("Admin Dashboard", page);
    }

    private void viewDonors() {
        String[] cols = {"ID", "Name", "Age", "Phone", "Blood Group", "Last Donation", "Status"};
        DefaultTableModel model = readOnlyModel(cols);
        JTable table = new JTable(model);
        styleTable(table);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM donors")) {
            while (rs.next()) {
                model.addRow(new Object[]{rs.getInt("id"), rs.getString("name"), rs.getInt("age"),
                        rs.getString("phone"), rs.getString("blood_group"),
                        rs.getString("last_donation"), rs.getString("status")});
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, "Error fetching donors: " + ex.getMessage());
        }

        setForm("All Registered Donors", tablePage(null, table, secondaryButton("Back to Dashboard", this::showAdminMenu)));
    }

    private void showRequestSummary() {
        int total = 0, approved = 0, rejected = 0, unitsFulfilled = 0;
        String[] cols = {"ID", "Patient Name", "Group", "Units", "Hospital", "Location", "Status"};
        DefaultTableModel model = readOnlyModel(cols);

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM requests ORDER BY id DESC")) {
            while (rs.next()) {
                int units = rs.getInt("units");
                String status = rs.getString("status");
                total++;
                if (status.startsWith("Approved")) {
                    approved++;
                    unitsFulfilled += units;
                } else {
                    rejected++;
                }
                model.addRow(new Object[]{rs.getInt("id"), rs.getString("name"), rs.getString("blood_group"),
                        units, rs.getString("hospital"), rs.getString("location"), status});
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, "Error generating report: " + ex.getMessage());
            return;
        }

        JPanel stats = new JPanel(new GridLayout(1, 4, 15, 0));
        stats.setOpaque(false);
        stats.add(createMetricCard("Total Requests", String.valueOf(total), ACCENT_NAVY));
        stats.add(createMetricCard("Approved", String.valueOf(approved), GREEN));
        stats.add(createMetricCard("Rejected", String.valueOf(rejected), PRIMARY_RED));
        stats.add(createMetricCard("Units Distributed", String.valueOf(unitsFulfilled), new Color(128, 0, 128)));

        JTable table = new JTable(model);
        styleTable(table);
        setForm("Automated Request Summary Report",
                tablePage(stats, table, secondaryButton("Back to Dashboard", this::showAdminMenu)));
    }

    private JPanel createMetricCard(String title, String value, Color headerColor) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(new LineBorder(BORDER_GRAY, 1));
        card.setBackground(CARD_BG);

        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setOpaque(true);
        titleLabel.setBackground(headerColor);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(new Font(FONT, Font.BOLD, 12));
        titleLabel.setBorder(new EmptyBorder(8, 5, 8, 5));

        JLabel valueLabel = new JLabel(value, SwingConstants.CENTER);
        valueLabel.setFont(new Font(FONT, Font.BOLD, 26));
        valueLabel.setForeground(headerColor);
        valueLabel.setBorder(new EmptyBorder(15, 5, 15, 5));

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private void addStockForm() {
        JPanel card = formCard(40, 50);
        JComboBox<String> group = createGroupCombo();
        JTextField units = createStyledTextField();

        addRow(card, 0, "Blood Group:", group);
        addRow(card, 1, "Units to Add:", units);

        JComponent update = button("Update Stock", () -> {
            try {
                int addUnits = Integer.parseInt(units.getText().trim());
                if (addUnits <= 0) {
                    JOptionPane.showMessageDialog(this, "Units must be greater than 0.");
                    return;
                }
                String sql = "UPDATE stock SET units = units + ? WHERE blood_group = ?";
                try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, addUnits);
                    pstmt.setString(2, group.getSelectedItem().toString());
                    pstmt.executeUpdate();
                    JOptionPane.showMessageDialog(this, "Stock updated successfully!");
                    showAdminMenu();
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Units must be a valid number.");
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Database Error: " + ex.getMessage());
            }
        });

        addButtons(card, 2, update, secondaryButton("Back to Dashboard", this::showAdminMenu));
        setForm("Add Physical Blood Stock", centered(card));
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new BloodBankSystem().setVisible(true));
    }
}