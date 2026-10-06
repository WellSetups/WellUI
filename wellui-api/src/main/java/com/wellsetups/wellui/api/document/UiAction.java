package com.wellsetups.wellui.api.document;

/**
 * Server-authorized action. Initial types are send_message, close_ui, and player_command.
 * Commands execute as the clicking player and never grant additional permissions.
 */
public record UiAction(String type, String value) { }
