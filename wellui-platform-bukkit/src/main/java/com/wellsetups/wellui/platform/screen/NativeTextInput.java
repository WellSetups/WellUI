package com.wellsetups.wellui.platform.screen;

import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.wellsetups.wellui.core.control.TextCapture;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Public vanilla 1.21.6+ dialog protocol, isolated from the session and renderer-independent document. */
final class NativeTextInput {
    private NativeTextInput() { }
    static MultiActionDialog dialog(TextCapture.Request request) {
        return dialog(request, "Clear");
    }
    static MultiActionDialog dialog(TextCapture.Request request, String clearLabel) {
        var input = new Input("value", new TextInputControl(240, Component.text(request.label()), true,
                request.value(), request.maxLength(), null));
        var common = new CommonDialogData(Component.text(request.label()), null, true, false, DialogAction.CLOSE, List.of(), List.of(input));
        return new MultiActionDialog(common, List.of(button(request, "save", Component.translatable("gui.done")),
                button(request, "clear", Component.text(clearLabel))), button(request, "cancel", Component.translatable("gui.cancel")), 2);
    }
    private static ActionButton button(TextCapture.Request request, String operation, Component label) {
        return new ActionButton(new CommonButtonData(label, null, 120),
                new DynamicCustomAction(new ResourceLocation("wellui:input/" + request.token() + "/" + operation), null));
    }
}
