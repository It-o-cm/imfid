package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ValuationRequest}: the {@code /valuation} input the POS
 * transmits verbatim to {@code /earn} (§15, §22). The class is a branch-free Jackson DTO, so
 * the whole surface is the implicit default constructor, its four public fields and the
 * {@link ValuationRequest.RequestItem} nested carrier; the cases assert the fresh nulls, the
 * never-null {@code items} default (§31.2) and that each field carries the exact reference
 * assigned.
 */
class ValuationRequestTest {

    /**
     * A freshly constructed request leaves every bound field null (Jackson populates them on
     * bind) so the DTO ships no accidental card, store or basket-date default.
     */
    @Test
    @DisplayName("default construction leaves the bound fields null")
    void defaultConstructionLeavesBoundFieldsNull() {
        ValuationRequest request = new ValuationRequest();
        assertNull(request.customerCode);
        assertNull(request.storeCode);
        assertNull(request.createdAt);
    }

    /**
     * The {@code items} field is initialised to a non-null empty list so a request with no
     * lines never yields a null collection (§31.2).
     */
    @Test
    @DisplayName("default construction initialises items to a non-null empty list")
    void defaultConstructionInitialisesItemsEmpty() {
        ValuationRequest request = new ValuationRequest();
        assertNotNull(request.items);
        assertTrue(request.items.isEmpty());
    }

    /**
     * The customerCode field is a plain carrier: it holds the exact reference assigned and
     * reads it back unchanged.
     */
    @Test
    @DisplayName("customerCode carries the exact reference assigned")
    void customerCodeCarriesAssignedReference() {
        ValuationRequest request = new ValuationRequest();
        String customerCode = "CARD-42";
        request.customerCode = customerCode;
        assertSame(customerCode, request.customerCode);
    }

    /**
     * The storeCode field is a plain carrier: it holds the exact reference assigned and reads
     * it back unchanged.
     */
    @Test
    @DisplayName("storeCode carries the exact reference assigned")
    void storeCodeCarriesAssignedReference() {
        ValuationRequest request = new ValuationRequest();
        String storeCode = "STORE-7";
        request.storeCode = storeCode;
        assertSame(storeCode, request.storeCode);
    }

    /**
     * The createdAt field is a plain carrier: it holds the exact basket date assigned and
     * reads it back unchanged.
     */
    @Test
    @DisplayName("createdAt carries the exact reference assigned")
    void createdAtCarriesAssignedReference() {
        ValuationRequest request = new ValuationRequest();
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        request.createdAt = createdAt;
        assertSame(createdAt, request.createdAt);
    }

    /**
     * The items field is a plain carrier: a reassigned list replaces the default and reads
     * back as the exact reference assigned.
     */
    @Test
    @DisplayName("items carries the exact reference assigned")
    void itemsCarriesAssignedReference() {
        ValuationRequest request = new ValuationRequest();
        List<ValuationRequest.RequestItem> items = new ArrayList<>();
        items.add(new ValuationRequest.RequestItem());
        request.items = items;
        assertSame(items, request.items);
    }

    /**
     * A freshly constructed line leaves every field null (Jackson populates them on bind) so
     * the nested carrier ships no accidental default.
     */
    @Test
    @DisplayName("RequestItem default construction leaves every field null")
    void requestItemDefaultConstructionLeavesFieldsNull() {
        ValuationRequest.RequestItem item = new ValuationRequest.RequestItem();
        assertNull(item.lineId);
        assertNull(item.produceEan);
        assertNull(item.quantity);
    }

    /**
     * The RequestItem lineId field is a plain carrier: it holds the exact reference assigned
     * and reads it back unchanged.
     */
    @Test
    @DisplayName("RequestItem lineId carries the exact reference assigned")
    void requestItemLineIdCarriesAssignedReference() {
        ValuationRequest.RequestItem item = new ValuationRequest.RequestItem();
        String lineId = "L1";
        item.lineId = lineId;
        assertSame(lineId, item.lineId);
    }

    /**
     * The RequestItem produceEan field is a plain carrier: it holds the exact reference
     * assigned and reads it back unchanged.
     */
    @Test
    @DisplayName("RequestItem produceEan carries the exact reference assigned")
    void requestItemProduceEanCarriesAssignedReference() {
        ValuationRequest.RequestItem item = new ValuationRequest.RequestItem();
        String produceEan = "3250391234567";
        item.produceEan = produceEan;
        assertSame(produceEan, item.produceEan);
    }

    /**
     * The RequestItem quantity field is a plain carrier: it holds the exact reference assigned
     * and reads it back unchanged.
     */
    @Test
    @DisplayName("RequestItem quantity carries the exact reference assigned")
    void requestItemQuantityCarriesAssignedReference() {
        ValuationRequest.RequestItem item = new ValuationRequest.RequestItem();
        Double quantity = Double.valueOf(2.5d);
        item.quantity = quantity;
        assertSame(quantity, item.quantity);
    }
}
