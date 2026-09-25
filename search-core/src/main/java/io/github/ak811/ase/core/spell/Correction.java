package io.github.ak811.ase.core.spell;

/**
 * A spelling suggestion.
 *
 * @param word    the normalized word, to be analyzed for searching
 * @param display the form to show the user
 */
public record Correction(String word, String display) {
}
