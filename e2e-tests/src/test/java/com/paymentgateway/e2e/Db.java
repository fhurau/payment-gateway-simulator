package com.paymentgateway.e2e;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Plain JDBC helpers for asserting on database state directly - api-gateway has no GET
 * /payments/{id} yet (deferred, see DEV-NOTES), so this is the only way to observe the async
 * ledger/notification outcome of a POST /payments call. */
public final class Db {

    private Db() {
    }

    private static Connection connect(String database) throws Exception {
        return DriverManager.getConnection(E2EEnvironment.jdbcUrl(database), "postgres", "postgres");
    }

    public static String paymentStatus(String paymentId) throws Exception {
        try (Connection c = connect("processor");
                PreparedStatement ps = c.prepareStatement("SELECT status FROM payments WHERE id = ?::uuid")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        }
    }

    public static String failureReason(String paymentId) throws Exception {
        try (Connection c = connect("processor");
                PreparedStatement ps = c.prepareStatement("SELECT failure_reason FROM payments WHERE id = ?::uuid")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("failure_reason") : null;
            }
        }
    }

    public static int ledgerEntryCount(String paymentId) throws Exception {
        try (Connection c = connect("processor");
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?::uuid")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static BigDecimal ledgerEntryAmount(String paymentId, String direction) throws Exception {
        try (Connection c = connect("processor");
                PreparedStatement ps = c.prepareStatement(
                        "SELECT amount FROM ledger_entries WHERE payment_id = ?::uuid AND direction = ?")) {
            ps.setString(1, paymentId);
            ps.setString(2, direction);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("amount") : null;
            }
        }
    }

    public static BigDecimal accountBalance(String accountId) throws Exception {
        try (Connection c = connect("processor");
                PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal("balance");
            }
        }
    }

    public static int notificationCount(String paymentId) throws Exception {
        try (Connection c = connect("notification");
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM notifications WHERE payment_id = ?::uuid")) {
            ps.setString(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static int idempotencyKeyRowCount(String idempotencyKey) throws Exception {
        try (Connection c = connect("gateway");
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM idempotency_keys WHERE key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
