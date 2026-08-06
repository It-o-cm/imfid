package com.intermarche.fidelity.graphql;

import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The GraphQL response types of the administration surface (§26.5) — plain projections
 * of the domain entities, keyed by business code, never by database id (§26.5). Keeping
 * them separate from the JPA entities avoids leaking lazy relations into the schema.
 * <p>
 * Fields are public for the SmallRye GraphQL serializer.
 */
public final class GraphQLTypes {

    /**
     * Non-instantiable holder of the GraphQL types.
     */
    private GraphQLTypes() {
    }

    /**
     * A rule projection (§13, §26.5).
     */
    public static final class RuleType {

        /** The stable rule code. */
        public String code;
        /** The rule type (factory code). */
        public String type;
        /** The printed label. */
        public String label;
        /** The window start. */
        public LocalDateTime validFrom;
        /** The window end, or null while open. */
        public LocalDateTime validTo;
        /** The evaluation priority. */
        public int priority;
        /** Whether the rule consumes its lines. */
        public boolean exclusive;
        /** The per-card monthly cap, or null. */
        public BigDecimal monthlyCapPerCard;
        /** Whether the rule is active. */
        public boolean active;
        /** The JSON specification. */
        public String specification;

        /**
         * Maps a rule entity to its projection.
         *
         * @param rule The rule entity.
         * @return The projection, or null when the entity is null.
         */
        public static RuleType of(FidelityRule rule) {
            if (rule == null) {
                return null;
            }
            RuleType type = new RuleType();
            type.code = rule.code;
            type.type = rule.type;
            type.label = rule.label;
            type.validFrom = rule.validFrom;
            type.validTo = rule.validTo;
            type.priority = rule.priority;
            type.exclusive = rule.exclusive;
            type.monthlyCapPerCard = rule.monthlyCapPerCard;
            type.active = rule.active;
            type.specification = rule.specification;
            return type;
        }
    }

    /**
     * A community projection (§13, §26.5).
     */
    public static final class CommunityType {

        /** The community code. */
        public String code;
        /** The community label. */
        public String label;
        /** The per-card monthly cap, or null. */
        public BigDecimal monthlyCap;
        /** The enrollment cap, or null. */
        public Integer enrollmentCap;
        /** The renewal window start month, or null. */
        public Integer renewalStartMonth;
        /** The renewal window end month, or null. */
        public Integer renewalEndMonth;
        /** The number of currently active members. */
        public long activeMembers;

        /**
         * Maps a community entity to its projection.
         *
         * @param community    The community entity.
         * @param activeMembers The active member count.
         * @return The projection, or null when the entity is null.
         */
        public static CommunityType of(FidelityCommunity community, long activeMembers) {
            if (community == null) {
                return null;
            }
            CommunityType type = new CommunityType();
            type.code = community.code;
            type.label = community.label;
            type.monthlyCap = community.monthlyCap;
            type.enrollmentCap = community.enrollmentCap;
            type.renewalStartMonth = community.renewalStartMonth;
            type.renewalEndMonth = community.renewalEndMonth;
            type.activeMembers = activeMembers;
            return type;
        }
    }

    /**
     * A program setting projection (§25.1, §26.5).
     */
    public static final class ProgramSettingType {

        /** The setting key. */
        public String key;
        /** The setting value. */
        public String value;

        /**
         * Maps a setting entity to its projection.
         *
         * @param setting The setting entity.
         * @return The projection, or null when the entity is null.
         */
        public static ProgramSettingType of(FidelityProgramSetting setting) {
            if (setting == null) {
                return null;
            }
            ProgramSettingType type = new ProgramSettingType();
            type.key = setting.key;
            type.value = setting.value;
            return type;
        }
    }

    /**
     * A card projection returned by the card mutations (§26.5).
     */
    public static final class CardType {

        /** The card number. */
        public String cardNumber;
        /** The account status. */
        public String status;
        /** The balance, euro at scale 2. */
        public BigDecimal balance;

        /**
         * Builds a card projection.
         *
         * @param cardNumber The card number.
         * @param status     The status.
         * @param balance    The balance.
         */
        public CardType(String cardNumber, String status, BigDecimal balance) {
            this.cardNumber = cardNumber;
            this.status = status;
            this.balance = balance;
        }
    }

    /**
     * A membership projection (§26.5).
     */
    public static final class MembershipType {

        /** The card number. */
        public String card;
        /** The community code. */
        public String community;
        /** The window start. */
        public LocalDate validFrom;
        /** The window end, or null. */
        public LocalDate validTo;

        /**
         * Maps a membership entity to its projection.
         *
         * @param membership The membership entity.
         * @return The projection, or null when the entity is null.
         */
        public static MembershipType of(FidelityMembership membership) {
            if (membership == null) {
                return null;
            }
            MembershipType type = new MembershipType();
            type.card = membership.account != null ? membership.account.cardNumber : null;
            type.community = membership.community != null ? membership.community.code : null;
            type.validFrom = membership.validFrom;
            type.validTo = membership.validTo;
            return type;
        }
    }

    /**
     * An activation projection (§26.5).
     */
    public static final class ActivationType {

        /** The card number. */
        public String card;
        /** The rule code enabled. */
        public String ruleCode;
        /** The period start. */
        public LocalDate periodStart;
        /** The period end, or null. */
        public LocalDate periodEnd;
        /** Whether the mission is completed. */
        public boolean missionDone;

        /**
         * Maps an activation entity to its projection.
         *
         * @param activation The activation entity.
         * @return The projection, or null when the entity is null.
         */
        public static ActivationType of(FidelityActivation activation) {
            if (activation == null) {
                return null;
            }
            ActivationType type = new ActivationType();
            type.card = activation.account != null ? activation.account.cardNumber : null;
            type.ruleCode = activation.ruleCode;
            type.periodStart = activation.periodStart;
            type.periodEnd = activation.periodEnd;
            type.missionDone = activation.missionDone;
            return type;
        }
    }
}
