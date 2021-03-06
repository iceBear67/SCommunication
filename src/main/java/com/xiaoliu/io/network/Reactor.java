package com.xiaoliu.io.network;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

import com.xiaoliu.io.SpinLock;

public class Reactor implements IReactor {

    private Selector _selector;

    private SpinLock _lock;

    private IReactorLogicHandler _handler;

    private List<Runnable> _tasks;

    private volatile boolean _isCancel;

    public Reactor(IReactorLogicHandler handler) throws IOException {
        _selector = Selector.open();
        _lock = new SpinLock();
        _handler = handler;
        _tasks = new LinkedList<>();
        _isCancel = false;
    }

    @Override
    public void run() {
        while(!_isCancel)
        {
            runOnce();
        }
    }

    @Override
    public void stop() {
        _isCancel = true;
        try {
            _selector.close();
        } catch (IOException e) {
            _handler.onError(e);
        }
    }

    @Override
    public void register(SocketChannel channel) {
        try 
        {
            channel.configureBlocking(false);
        } 
        catch (IOException e) 
        {
            _handler.onError(channel,e);
        }
        runInLoop(()->{
            try 
            {
               channel.register(_selector,SelectionKey.OP_READ); 
            } 
            catch (ClosedChannelException e) 
            {
                _handler.onError(channel,e);
            }
        });
    }

    @Override
    public void register(ServerSocketChannel channel) {
        try 
        {
            channel.configureBlocking(false);
        } 
        catch (IOException e) 
        {
            _handler.onError(channel,e);
        }
        runInLoop(()->
        {
            try 
            {
               channel.register(_selector,SelectionKey.OP_ACCEPT); 
            } 
            catch (ClosedChannelException e) 
            {
                _handler.onError(channel,e);
            }
        });
    }

    @Override
    public void enableWrite(SocketChannel channel) {
        runInLoop(()->
        {
            Iterator<SelectionKey> ite = _selector.keys().iterator();
            while(ite.hasNext())
            {
                SelectionKey key = ite.next();
                if(key != null && key.isValid() && key.channel() == channel)
                {
                    int ops = key.interestOps();
                    ops |= SelectionKey.OP_WRITE;
                    key.interestOps(ops);

                    return;
                }
            }
        });
    }

    @Override
    public void disableWrite(SocketChannel channel) {
        runInLoop(()->
        {
            Iterator<SelectionKey> ite = _selector.keys().iterator();
            while(ite.hasNext())
            {
                SelectionKey key = ite.next();
                if(key != null && key.isValid() && key.channel() == channel)
                {
                    int ops = key.interestOps();
                    ops ^= SelectionKey.OP_WRITE;
                    key.interestOps(ops);
                }
            }
        });
    }

    // JVM bug:
    // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=2147719
    // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6403933
    // 重建selector
    private void rebuildSelector() throws IOException {
        // 创建新selector
        Selector selector = Selector.open();
        // 将原来selector的key移动到新建的selector上
        for (SelectionKey key : _selector.keys()) {
            if(key != null && key.isValid())
            {
                key.channel().register(selector, key.interestOps());
                key.cancel();
            }
        }
        // 交换两个selector
        Selector tmp = _selector;
        _selector = selector;
        // 关闭旧的selector
        tmp.close();
    }

    private List<Runnable> getTasks() {
        List<Runnable> tasks = new LinkedList<>();
        try {
            _lock.lock();
            if (_tasks.size() > 0) {
                List<Runnable> tmp = tasks;
                tasks = _tasks;
                _tasks = tmp;
            }
        } finally {
            _lock.unlock();
        }
        return tasks;
    }

    private boolean runTasks() {
        List<Runnable> tasks = getTasks();
        if (tasks.isEmpty()) {
            return false;
        }
        for (Runnable task : tasks) {
            task.run();
        }
        return true;
    }

    private void handleEvent(SelectionKey key) {
        if (key.isValid()) {
            if (key.isReadable()) {
                ByteBuffer buf = ByteBuffer.allocateDirect(4096);
                SocketChannel channel = (SocketChannel) key.channel();
                try {
                    channel.read(buf);
                    _handler.onRead(channel, buf);
                } catch (ClosedChannelException e) {
                    _handler.onClose(channel);
                } catch (IOException e) {
                    _handler.onError(channel,e);
                }
            } else if (key.isWritable()) {
                SocketChannel channel = (SocketChannel) key.channel();
                _handler.onWrite(channel,this);
            } else if (key.isAcceptable()) {
                ServerSocketChannel channel = (ServerSocketChannel) key.channel();
                try {
                    _handler.onAccept(channel.accept());
                } catch (Exception e) {
                    _handler.onError(channel,e);
                }
            } else {
                if (key.channel() instanceof SocketChannel) {
                    SocketChannel channel = (SocketChannel) key.channel();
                    _handler.onClose(channel);
                }
            }
        }
    }

    private void runOnce() {
        try {
            int numOfEvent = _selector.select();
            if (numOfEvent > 0) {
                Set<SelectionKey> events = _selector.selectedKeys();
                Iterator<SelectionKey> ite = events.iterator();
                while (ite.hasNext()) {
                    SelectionKey key = ite.next();
                    ite.remove();
                    handleEvent(key);
                }
            }
            boolean isTask = runTasks();
            if (!isTask && numOfEvent < 1 && !_isCancel) {
                rebuildSelector();
            }
        } catch (Exception e) {
            _handler.onError(e);
        }
    }

    private void runInLoop(Runnable task)
    {
        try
        {
            _lock.lock();
            _tasks.add(task);
        }
        finally
        {
            _lock.unlock();
        }
        _selector.wakeup();
    }

    @Override
    public void postWrite(SocketChannel channel, ByteBuffer data) {
        _handler.postWrite(channel, data, this);
    }

    @Override
    public void closeChannel(SocketChannel channel) {
        runInLoop(()->{
            _handler.closeChannel(channel);
        });
    }
}
