package com.wellsetups.wellui.editor;

import com.wellsetups.wellui.core.AssetStore;
import com.wellsetups.wellui.core.DocumentCodec;
import com.wellsetups.wellui.core.DocumentValidator;
import com.wellsetups.wellui.core.ExampleProject;
import com.wellsetups.wellui.core.ProjectRepository;
import com.wellsetups.wellui.core.ResourcePackBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;

/** Local editor evaluation without Minecraft. Deploy validates, saves and exports a format-15 pack. */
public final class EditorMain {
    private EditorMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "wellui-data").toAbsolutePath();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8787;
        var codec = new DocumentCodec();
        var projects = new ProjectRepository(root.resolve("projects"), codec);
        var assets = new AssetStore(root.resolve("assets"), EditorServer.MAX_ASSET_BYTES);
        if (projects.list().isEmpty()) projects.save(ExampleProject.create());
        var packs = new ResourcePackBuilder(assets, root.resolve("packs"));
        var server = new EditorServer(new InetSocketAddress("127.0.0.1", port), root,
                projects, assets, codec, new DocumentValidator(), document -> {
                    var pack = packs.build(document, 15);
                    projects.save(document);
                    return new DeploymentResult(true, List.of("editor.deploy.export_only"), pack.sha256());
                });
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "wellui-editor-shutdown"));
        server.start();
        var messages = ResourceBundle.getBundle("editor_messages");
        System.out.println(messages.getString("standalone.notice"));
        System.out.println(messages.getString("standalone.open") + " http://127.0.0.1:" + server.port() + "/#token=" + server.issueToken());
        new CountDownLatch(1).await();
    }
}
