package com.widget.noname.server.nonameserver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.widget.noname.server.nonameserver.pojo.Config;
import com.widget.noname.server.nonameserver.pojo.Event;
import com.widget.noname.server.nonameserver.pojo.Room;
import com.widget.noname.server.nonameserver.util.ServerUtil;
import com.widget.noname.server.nonameserver.util.Util;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.HashedWheelTimer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.yeauty.annotation.*;
import org.yeauty.pojo.Session;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@Component
@ServerEndpoint(port = "8080", allIdleTimeSeconds = "60000", maxFramePayloadLength = "2621440")
public class WebSocketServer {

    private static final String MSG_UPDATE_CLIENTS = "updateclients";
    private static final String MSG_UPDATE_ROOMS = "updaterooms";
    private static final String MSG_ROOM_LIST = "roomlist";
    private static final String MSG_CREATE_ROOM = "createroom";
    private static final String MSG_CONNECTION = "onconnection";
    private static final String MSG_ONMESSAGE = "onmessage";
    private static final String MSG_ENTER_FAILED = "enterroomfailed";
    private static final String MSG_SELF_CLOSE = "selfclose";
    private static final String MSG_ON_CLOSE = "onclose";
    private static final String MSG_UPDATE_EVENTS = "updateevents";

    private static final String MSG_DENIED = "denied";
    private static final String MSG_BANNED = "banned";
    private static final String MSG_KEY = "key";
    private static final String MSG_EVENT_DENIED = "eventsdenied";
    private static final String MSG_TOTAL = "total";
    private static final String MSG_TIME = "time";
    private static final String MSG_BAN = "ban";
    private static final String HEART_BEAT = "heartbeat";
    private static final String SERVER = "server";
    private static final String JOIN = "join";
    private static final String LEAVE = "leave";

    private static final String FUN_ENTER = "enter";
    private static final String FUN_CHANGE_AVATAR = "changeAvatar";
    private static final String FUN_CREATE = "create";
    private static final String FUN_KEY = "key";
    private static final String FUN_EVENT = "events";
    private static final String FUN_CONFIG = "config";
    private static final String FUN_STATUS = "status";
    private static final String FUN_SEND = "send";
    private static final String FUN_CLOSE = "close";

    private static final String KEY_ID = "key_id";

    private static final ConcurrentHashMap<String, WebSocketClient> clients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Event> events = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<String> bannedIps = new CopyOnWriteArrayList<>();

    private static final HashedWheelTimer timer = new HashedWheelTimer(3, TimeUnit.SECONDS, 100);

    @BeforeHandshake
    public void handshake(Session session, HttpHeaders headers) {
//        session.setSubprotocols("stomp");
    }

    @OnOpen
    public void onOpen(@NonNull Session session) {
        WebSocketClient ws = new WebSocketClient(session);

        if (bannedIps.contains(session.remoteAddress().toString())) {
            ws.sendl(MSG_DENIED, MSG_BANNED);
            timer.newTimeout(timeout -> session.close(), 500, TimeUnit.MILLISECONDS);
            return;
        }

        String id = ws.getWsid();
        session.setAttribute(KEY_ID, id);
        clients.put(id, ws);

        ws.sendl(MSG_ROOM_LIST, Util.getRoomlist(rooms), events, Util.getClientlist(clients), id);
    }

    @OnClose
    public void onClose(Session session) throws IOException {
        String wsid = session.getAttribute(KEY_ID).toString();
        WebSocketClient ws = clients.get(wsid);
        closeSocketSafely(ws);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        throwable.printStackTrace();

        String wsid = session.getAttribute(KEY_ID).toString();
        WebSocketClient ws = clients.get(wsid);
        closeSocketSafely(ws);
        session.close();
    }

    @OnMessage
    public void onMessage(Session session, String message) {
        String wsid = session.getAttribute(KEY_ID).toString();
        WebSocketClient ws = clients.get(wsid);

        if (null != ws) {
            handleClientMessage(ws, message);
        } else {
            session.close();
            System.out.println("unknown client, close." + wsid);
        }
    }

    @OnEvent
    public void onEvent(Session session, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent idleStateEvent = (IdleStateEvent) evt;

            if (idleStateEvent.state() == IdleState.ALL_IDLE) {
                session.close();
            }
        }
    }

    /******************************* 内部方法 ********************************************/
    private void handleClientMessage(WebSocketClient ws, String message) {
        if (HEART_BEAT.equals(message)) {
            return;
        }

        WebSocketClient owner = ws.getOwner();

        if (null != owner) {
            owner.sendl(MSG_ONMESSAGE, ws.getWsid(), message);
        } else {
            List<String> list = JSONArray.parseArray(message, String.class);
            dispatchMessage(ws, list);
        }
    }

    public void dispatchMessage(WebSocketClient ws, List<String> msg) {
        if ((null != msg) && (msg.size() > 1) && SERVER.equals(msg.get(0))) {
            String type = msg.get(1);

            switch (type) {
                case FUN_KEY: {
                    keyCheck(ws, msg.get(2));
                    break;
                }

                case FUN_CHANGE_AVATAR: {
                    ws.setNickName(Util.getNickName(msg.get(2)));
                    ws.setAvatar(msg.get(3));
                    updateClients();
                    break;
                }

                case FUN_CREATE: {
                    createRoom(ws, msg.get(2), msg.get(3), msg.get(4));
                    break;
                }

                case FUN_ENTER: {
                    enterRoom(ws, msg.get(2), msg.get(3), msg.get(4));
                    break;
                }

                case FUN_EVENT: {
                    int size = msg.size();

                    if (size == 4) {
                        createEvent(ws, msg.get(2), msg.get(3), null);
                    } else if (size == 5) {
                        createEvent(ws, msg.get(2), msg.get(3), msg.get(4));
                    }
                    break;
                }
                case FUN_CONFIG: {
                    createConfig(ws, msg.get(2));
                    break;
                }
                case FUN_STATUS: {
                    if (!ServerUtil.isEmpty(msg.get(2))) {
                        ws.setStatus(msg.get(2));
                    } else {
                        ws.setStatus(null);
                    }

                    updateClients();
                    break;
                }
                case FUN_SEND: {
                    sendMsg(msg.get(2), msg.get(3));
                    break;
                }
                case FUN_CLOSE: {
                    closeSocketSafely(ws);
                    break;
                }
            }
        }
    }

    private void createEvent(WebSocketClient ws, String config, String id, String type) {
        if (bannedIps.contains(id)) {
            ws.close();
            return;
        }

        boolean changed = false;

        if (null != config && null != id) {
            Event cfg = null;
            try {
                cfg = JSON.parseObject(config, Event.class);
            } catch (Exception ignored) {
            }

            long time = new Date().getTime();

            if (null != cfg) {
                if (events.size() >= 20) {
                    ws.sendl(MSG_EVENT_DENIED, MSG_TOTAL);
                } else if (cfg.getUtc() <= time) {
                    ws.sendl(MSG_EVENT_DENIED, MSG_TIME);
                } else {
                    cfg.setNickname(Util.getNickName(cfg.getNickname()));
                    cfg.setCreator(id);
                    cfg.setId(Util.getId());
                    cfg.addMembers(id);
                    events.add(cfg);
                    changed = true;
                }
            } else {
                changed = true;

                events.forEach(event -> {
                    if (Objects.equals(event.getId(), config)) {
                        if (Objects.equals(type, JOIN)) {
                            if (!event.containMembers(id)) {
                                event.addMembers(id);
                            }
                        } else if (Objects.equals(type, LEAVE)) {
                            event.leaveMembers(id);
                        }
                    }
                });
            }
        }

        if (changed) {
            updateEvents();
        }
    }

    private void updateClients() {
        ArrayList<JSONArray> clientlist = Util.getClientlist(clients);

        clients.forEach((s, ws) -> {
            if (!ws.isInRoom()) {
                ws.sendl(MSG_UPDATE_CLIENTS, clientlist);
            }
        });
    }

    private void createRoom(WebSocketClient ws, String key, String nickname, String avatar) {
        if (!Objects.equals(key, ws.getOnlineKey())) {
            return;
        }

        ws.setNickName(Util.getNickName(nickname));
        ws.setAvatar(avatar);

        Room room = new Room();
        ws.setRoom(room);
        ws.setStatus(null);
        room.setOwner(ws);
        room.setKey(key);
        room.enter(ws);
        rooms.put(key, room);

        ws.sendl(MSG_CREATE_ROOM, key);
    }

    private void keyCheck(WebSocketClient ws, String message) {
        try {
            List<String> values = JSONArray.parseArray(message, String.class);

            if (null != values && values.size() > 0) {
                ws.setOnlineKey(values.get(0));
                ws.setVersion(values.get(1));
            } else {
                ws.sendl(MSG_DENIED, MSG_KEY);
                ws.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createConfig(WebSocketClient ws, String config) {
        Room room = ws.getRoom();

        if (null != room && room.getOwner() == ws) {
            if (room.isServerMode()) {
                room.setServerMode(false);

                if (null != ws.getOnConfig()) {
                    WebSocketClient onConfig = clients.get(ws.getOnConfig().getWsid());

                    if (null != onConfig) {
                        ws.getOnConfig().setOwner(ws);
                    }

                    ws.setOnConfig(null);
                }
            }

            room.setConfig(config);
        }

        updateRooms();
    }

    private void updateRooms() {
        ArrayList<JSONArray> roomlist = Util.getRoomlist(rooms);
        ArrayList<JSONArray> clientlist = Util.getClientlist(clients);

        clients.forEach((s, ws) -> {
            if (!ws.isInRoom()) {
                ws.sendl(MSG_UPDATE_ROOMS, roomlist, clientlist);
            }
        });
    }

    private void updateEvents() {
        if (events.size() > 0) {
            long time = new Date().getTime();
            events.removeIf(next -> next.getUtc() <= time);
        }

        clients.forEach((s, ws) -> {
            if (!ws.isInRoom()) {
                ws.sendl(MSG_UPDATE_EVENTS, events);
            }
        });
    }

    private void enterRoom(WebSocketClient ws, String key, String nickname, String avatar) {
        ws.setNickName(ServerUtil.checkNikeName(nickname));
        ws.setAvatar(avatar);
        Room room = rooms.get(key);

        if (null == room) {
            ws.sendl(MSG_ENTER_FAILED);
            return;
        }

        ws.setRoom(room);
        room.enter(ws);
        ws.setStatus(null);

        if (room.getOwner() != null) {
            Config config = room.getConfig2();

            if ((room.getConfig() == null) || ((null != config) && config.isGameStarted() && (!config.isObserve() || !config.isObserveReady()))) {
                ws.sendl(MSG_ENTER_FAILED);
            } else {
                ws.setOwner(room.getOwner());
                ws.getOwner().sendl(MSG_CONNECTION, ws.getWsid());
            }

            updateRooms();
        }
    }

    private void sendMsg(String id, String msg) {
        clients.forEach((s, client) -> {
            if (Objects.equals(id, client.getWsid())) {
                client.getSession().sendText(msg);
            }
        });
    }

    public void closeSocketSafely(WebSocketClient ws) {
        if (null == ws) {
            return;
        }

        Iterator<String> it = rooms.keySet().stream().iterator();

        while (it.hasNext()) {
            Room room = rooms.get(it.next());

            if (Objects.equals(room.getOwner(), ws)) {
                room.getPlayers().forEach((s, client) -> client.getSession().close());
                room.destroy();
                rooms.remove(room.getKey());
            }
        }

        if (clients.contains(ws)) {
            if (ws.getOwner() != null) {
                ws.getOwner().sendl(MSG_ON_CLOSE, ws.getWsid());
                ws.setOwner(null);
            }

            if (ws.getRoom() != null) {
                ws.getRoom().leave(ws);
            }

            clients.remove(ws.getWsid());
        }

        if (ws.isInRoom()) {
            updateRooms();
        } else {
            updateClients();
        }
    }
}
