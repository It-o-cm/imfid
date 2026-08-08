package com.intermarche.fidelity.graphql;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Plain unit coverage of {@link GraphQLTypes} projections (§26.5): both arms of every
 * null guard and of every account/community ternary, plus the direct constructor of the
 * card projection and the non-instantiable holder.
 */
class GraphQLTypesTest {

    /**
     * The private holder constructor is reachable by reflection and constructs cleanly.
     *
     * @throws Exception When reflective construction fails.
     */
    @Test
    void holderConstructorIsPrivateButInvokable() throws Exception {
        Constructor<GraphQLTypes> constructor = GraphQLTypes.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Assertions.assertNotNull(constructor.newInstance());
    }

    /**
     * A null rule maps to a null projection.
     */
    @Test
    void ruleOfNullReturnsNull() {
        Assertions.assertNull(GraphQLTypes.RuleType.of(null));
    }

    /**
     * A non-null rule copies every field verbatim into the projection.
     */
    @Test
    void ruleOfCopiesEveryField() {
        FidelityRule rule = new FidelityRule();
        rule.code = "R1";
        rule.type = FidelityRule.TYPE_COMMUNITY_EARN;
        rule.label = "Community earn";
        rule.validFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        rule.validTo = LocalDateTime.of(2026, 12, 31, 23, 59);
        rule.priority = 7;
        rule.exclusive = true;
        rule.monthlyCapPerCard = new BigDecimal("12.50");
        rule.active = false;
        rule.specification = "{\"k\":1}";
        GraphQLTypes.RuleType projection = GraphQLTypes.RuleType.of(rule);
        Assertions.assertEquals("R1", projection.code);
        Assertions.assertEquals(FidelityRule.TYPE_COMMUNITY_EARN, projection.type);
        Assertions.assertEquals("Community earn", projection.label);
        Assertions.assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), projection.validFrom);
        Assertions.assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59), projection.validTo);
        Assertions.assertEquals(7, projection.priority);
        Assertions.assertTrue(projection.exclusive);
        Assertions.assertEquals(0, new BigDecimal("12.50").compareTo(projection.monthlyCapPerCard));
        Assertions.assertFalse(projection.active);
        Assertions.assertEquals("{\"k\":1}", projection.specification);
    }

    /**
     * A non-null rule with a null cap preserves the null cap.
     */
    @Test
    void ruleOfKeepsNullCap() {
        FidelityRule rule = new FidelityRule();
        rule.monthlyCapPerCard = null;
        Assertions.assertNull(GraphQLTypes.RuleType.of(rule).monthlyCapPerCard);
    }

    /**
     * A null community maps to a null projection.
     */
    @Test
    void communityOfNullReturnsNull() {
        Assertions.assertNull(GraphQLTypes.CommunityType.of(null, 3L));
    }

    /**
     * A non-null community copies every field and the supplied member count.
     */
    @Test
    void communityOfCopiesEveryField() {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "C1";
        community.label = "Parents";
        community.monthlyCap = new BigDecimal("5.00");
        community.enrollmentCap = 100;
        community.renewalStartMonth = 3;
        community.renewalEndMonth = 5;
        GraphQLTypes.CommunityType projection = GraphQLTypes.CommunityType.of(community, 42L);
        Assertions.assertEquals("C1", projection.code);
        Assertions.assertEquals("Parents", projection.label);
        Assertions.assertEquals(0, new BigDecimal("5.00").compareTo(projection.monthlyCap));
        Assertions.assertEquals(100, projection.enrollmentCap);
        Assertions.assertEquals(3, projection.renewalStartMonth);
        Assertions.assertEquals(5, projection.renewalEndMonth);
        Assertions.assertEquals(42L, projection.activeMembers);
    }

    /**
     * A null setting maps to a null projection.
     */
    @Test
    void settingOfNullReturnsNull() {
        Assertions.assertNull(GraphQLTypes.ProgramSettingType.of(null));
    }

    /**
     * A non-null setting copies the key and value.
     */
    @Test
    void settingOfCopiesKeyAndValue() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.key = FidelityProgramSetting.KEY_PROGRAM_ZONE;
        setting.value = "Europe/Paris";
        GraphQLTypes.ProgramSettingType projection = GraphQLTypes.ProgramSettingType.of(setting);
        Assertions.assertEquals(FidelityProgramSetting.KEY_PROGRAM_ZONE, projection.key);
        Assertions.assertEquals("Europe/Paris", projection.value);
    }

    /**
     * The card projection constructor stores every argument verbatim.
     */
    @Test
    void cardTypeConstructorStoresFields() {
        GraphQLTypes.CardType card = new GraphQLTypes.CardType("9990001", "ACTIVE", new BigDecimal("3.20"));
        Assertions.assertEquals("9990001", card.cardNumber);
        Assertions.assertEquals("ACTIVE", card.status);
        Assertions.assertEquals(0, new BigDecimal("3.20").compareTo(card.balance));
    }

    /**
     * A null membership maps to a null projection.
     */
    @Test
    void membershipOfNullReturnsNull() {
        Assertions.assertNull(GraphQLTypes.MembershipType.of(null));
    }

    /**
     * A membership with both account and community present exposes their codes (true arms).
     */
    @Test
    void membershipOfWithAccountAndCommunity() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "7770002";
        FidelityCommunity community = new FidelityCommunity();
        community.code = "C9";
        FidelityMembership membership = new FidelityMembership();
        membership.account = account;
        membership.community = community;
        membership.validFrom = LocalDate.of(2026, 2, 1);
        membership.validTo = LocalDate.of(2026, 8, 31);
        GraphQLTypes.MembershipType projection = GraphQLTypes.MembershipType.of(membership);
        Assertions.assertEquals("7770002", projection.card);
        Assertions.assertEquals("C9", projection.community);
        Assertions.assertEquals(LocalDate.of(2026, 2, 1), projection.validFrom);
        Assertions.assertEquals(LocalDate.of(2026, 8, 31), projection.validTo);
    }

    /**
     * A membership with a null account and a null community yields null codes (false arms).
     */
    @Test
    void membershipOfWithoutAccountAndCommunity() {
        FidelityMembership membership = new FidelityMembership();
        membership.account = null;
        membership.community = null;
        membership.validFrom = null;
        membership.validTo = null;
        GraphQLTypes.MembershipType projection = GraphQLTypes.MembershipType.of(membership);
        Assertions.assertNull(projection.card);
        Assertions.assertNull(projection.community);
        Assertions.assertNull(projection.validFrom);
        Assertions.assertNull(projection.validTo);
    }

    /**
     * A null activation maps to a null projection.
     */
    @Test
    void activationOfNullReturnsNull() {
        Assertions.assertNull(GraphQLTypes.ActivationType.of(null));
    }

    /**
     * An activation with a present account exposes its card number (true arm).
     */
    @Test
    void activationOfWithAccount() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "5550003";
        FidelityActivation activation = new FidelityActivation();
        activation.account = account;
        activation.ruleCode = "R42";
        activation.periodStart = LocalDate.of(2026, 3, 1);
        activation.periodEnd = LocalDate.of(2026, 3, 31);
        activation.missionDone = true;
        GraphQLTypes.ActivationType projection = GraphQLTypes.ActivationType.of(activation);
        Assertions.assertEquals("5550003", projection.card);
        Assertions.assertEquals("R42", projection.ruleCode);
        Assertions.assertEquals(LocalDate.of(2026, 3, 1), projection.periodStart);
        Assertions.assertEquals(LocalDate.of(2026, 3, 31), projection.periodEnd);
        Assertions.assertTrue(projection.missionDone);
    }

    /**
     * An activation with a null account yields a null card (false arm).
     */
    @Test
    void activationOfWithoutAccount() {
        FidelityActivation activation = new FidelityActivation();
        activation.account = null;
        activation.ruleCode = "R43";
        activation.periodStart = null;
        activation.periodEnd = null;
        activation.missionDone = false;
        GraphQLTypes.ActivationType projection = GraphQLTypes.ActivationType.of(activation);
        Assertions.assertNull(projection.card);
        Assertions.assertEquals("R43", projection.ruleCode);
        Assertions.assertNull(projection.periodStart);
        Assertions.assertNull(projection.periodEnd);
        Assertions.assertFalse(projection.missionDone);
    }
}
