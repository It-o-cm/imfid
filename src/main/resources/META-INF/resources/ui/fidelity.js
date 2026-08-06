/*
 * imfid admin — shared client helpers.
 *
 * Renders a real, scannable EAN-13 barcode from a card number (§33.1): the card sheet
 * shows it so there is physical matter to scan in a demonstration. Any <svg class="ean13"
 * data-ean="..."> on the page is filled on load.
 */
(function () {
    'use strict';

    // Parity pattern of the six left digits, selected by the first digit (EAN-13).
    var FIRST = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG',
        'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];
    // 7-module codes for L, G and R digit encodings.
    var L = ['0001101', '0011001', '0010011', '0111101', '0100011',
        '0110001', '0101111', '0111011', '0110111', '0001011'];
    var G = ['0100111', '0110011', '0011011', '0100001', '0011101',
        '0111001', '0000101', '0010001', '0001001', '0010111'];
    var R = ['1110010', '1100110', '1101100', '1000010', '1011100',
        '1001110', '1010000', '1000100', '1001000', '1110100'];

    /**
     * Builds the 95-module bit string of an EAN-13 number (guards included).
     * @param {string} ean the 13 digits
     * @returns {string|null} the module bits, or null when the number is not 13 digits
     */
    function modules(ean) {
        if (!/^[0-9]{13}$/.test(ean)) {
            return null;
        }
        var digits = ean.split('').map(Number);
        var pattern = FIRST[digits[0]];
        var bits = '101'; // start guard
        for (var i = 1; i <= 6; i++) {
            bits += (pattern[i - 1] === 'L' ? L : G)[digits[i]];
        }
        bits += '01010'; // middle guard
        for (var j = 7; j <= 12; j++) {
            bits += R[digits[j]];
        }
        bits += '101'; // end guard
        return bits;
    }

    /**
     * Draws the barcode into an <svg class="ean13"> element.
     * @param {SVGElement} svg the target element carrying data-ean
     */
    function draw(svg) {
        var ean = svg.getAttribute('data-ean') || '';
        var bits = modules(ean);
        if (!bits) {
            svg.outerHTML = '<span class="placeholder-note">Numéro non EAN-13 : ' + ean + '</span>';
            return;
        }
        var unit = 2;
        var height = 70;
        var quiet = 11 * unit;
        var width = quiet * 2 + bits.length * unit;
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + (height + 18));
        svg.setAttribute('width', width);
        svg.setAttribute('height', height + 18);
        var svgns = 'http://www.w3.org/2000/svg';
        var x = quiet;
        for (var i = 0; i < bits.length; i++) {
            if (bits[i] === '1') {
                var rect = document.createElementNS(svgns, 'rect');
                rect.setAttribute('x', x);
                rect.setAttribute('y', 0);
                rect.setAttribute('width', unit);
                rect.setAttribute('height', height);
                rect.setAttribute('fill', '#16181d');
                svg.appendChild(rect);
            }
            x += unit;
        }
        var text = document.createElementNS(svgns, 'text');
        text.setAttribute('x', width / 2);
        text.setAttribute('y', height + 14);
        text.setAttribute('text-anchor', 'middle');
        text.setAttribute('font-family', 'monospace');
        text.setAttribute('font-size', '13');
        text.setAttribute('letter-spacing', '2');
        text.setAttribute('fill', '#16181d');
        text.textContent = ean;
        svg.appendChild(text);
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('svg.ean13').forEach(draw);
    });
})();
