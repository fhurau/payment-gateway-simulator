package com.paymentgateway.paymentprocessor.reconciliation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The four reconciliation checks from DESIGN.md §12. Empty list = healthy. */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ReconciliationIssue> checkAll() {
        List<ReconciliationIssue> issues = new ArrayList<>();
        issues.addAll(completedPaymentsWithWrongLedgerEntryCount());
        issues.addAll(consumedEventsMissingPaymentRow());
        issues.addAll(globalDebitCreditMismatch());
        issues.addAll(accountBalanceMismatches());
        return issues;
    }

    private List<ReconciliationIssue> completedPaymentsWithWrongLedgerEntryCount() {
        return jdbcTemplate.query(
                "SELECT p.id, (SELECT COUNT(*) FROM ledger_entries WHERE payment_id = p.id) AS entry_count "
                        + "FROM payments p WHERE p.status = 'COMPLETED' "
                        + "AND (SELECT COUNT(*) FROM ledger_entries WHERE payment_id = p.id) <> 2",
                (rs, rowNum) -> new ReconciliationIssue("LEDGER_ENTRY_COUNT_MISMATCH",
                        "payment " + rs.getString("id") + " is COMPLETED but has "
                                + rs.getInt("entry_count") + " ledger entries (expected 2)"));
    }

    private List<ReconciliationIssue> consumedEventsMissingPaymentRow() {
        return jdbcTemplate.query(
                "SELECT ce.event_id, ce.payment_id FROM consumed_events ce "
                        + "WHERE ce.event_type = 'payment.created' AND ce.payment_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = ce.payment_id)",
                (rs, rowNum) -> new ReconciliationIssue("MISSING_PAYMENT_ROW",
                        "event " + rs.getString("event_id") + " for payment " + rs.getString("payment_id")
                                + " was consumed but has no payments row"));
    }

    private List<ReconciliationIssue> globalDebitCreditMismatch() {
        record Totals(BigDecimal debit, BigDecimal credit) {
        }
        Totals totals = jdbcTemplate.queryForObject(
                "SELECT (SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE direction='DEBIT') AS total_debit, "
                        + "(SELECT COALESCE(SUM(amount),0) FROM ledger_entries WHERE direction='CREDIT') AS total_credit",
                (rs, rowNum) -> new Totals(rs.getBigDecimal("total_debit"), rs.getBigDecimal("total_credit")));
        if (totals != null && totals.debit().compareTo(totals.credit()) != 0) {
            return List.of(new ReconciliationIssue("DOUBLE_ENTRY_BREAKAGE",
                    "total debits " + totals.debit() + " != total credits " + totals.credit()));
        }
        return List.of();
    }

    private List<ReconciliationIssue> accountBalanceMismatches() {
        return jdbcTemplate.query(
                "WITH ledger_net AS ("
                        + "  SELECT account_id, SUM(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END) AS net "
                        + "  FROM ledger_entries GROUP BY account_id"
                        + ") "
                        + "SELECT a.account_id, a.opening_balance, a.balance, COALESCE(ln.net,0) AS net "
                        + "FROM accounts a LEFT JOIN ledger_net ln ON ln.account_id = a.account_id "
                        + "WHERE a.opening_balance + COALESCE(ln.net,0) <> a.balance",
                (rs, rowNum) -> new ReconciliationIssue("ACCOUNT_BALANCE_MISMATCH",
                        "account " + rs.getString("account_id") + " balance " + rs.getBigDecimal("balance")
                                + " != opening balance " + rs.getBigDecimal("opening_balance") + " + net ledger "
                                + rs.getBigDecimal("net")));
    }
}
