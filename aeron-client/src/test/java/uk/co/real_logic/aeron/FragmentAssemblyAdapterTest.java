package uk.co.real_logic.aeron;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.Header;

import java.nio.ByteOrder;

import static org.junit.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class FragmentAssemblyAdapterTest
{
    private static final int SESSION_ID = 777;

    private final DataHandler delegateDataHandler = mock(DataHandler.class);
    private final AtomicBuffer logBuffer = mock(AtomicBuffer.class);
    private final Header header = mock(Header.class);
    private final FragmentAssemblyAdapter adapter = new FragmentAssemblyAdapter(delegateDataHandler);

    @Before
    public void setup()
    {
        when(header.sessionId()).thenReturn(SESSION_ID);
        when(header.buffer()).thenReturn(logBuffer);
        when(logBuffer.getInt(anyInt(), any(ByteOrder.class))).thenReturn(SESSION_ID);
    }

    @Test
    public void shouldPassThroughUnfragmentedMessage()
    {
        when(header.flags()).thenReturn(FrameDescriptor.UNFRAGMENTED);
        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[128]);
        final int offset = 8;
        final int length = 32;

        adapter.onData(srcBuffer, offset, length, header);

        verify(delegateDataHandler, times(1)).onData(srcBuffer, offset, length, header);
    }

    @Test
    public void shouldAssembleTwoPartMessage()
    {
        when(header.flags()).thenReturn(FrameDescriptor.BEGIN_FRAG)
                            .thenReturn(FrameDescriptor.END_FRAG);

        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);
        final int offset = 0;
        final int length = srcBuffer.capacity() / 2;

        srcBuffer.setMemory(0, length, (byte)65);
        srcBuffer.setMemory(length, length, (byte)66);

        adapter.onData(srcBuffer, offset, length, header);
        adapter.onData(srcBuffer, length, length, header);

        final ArgumentCaptor<AtomicBuffer> bufferArg = ArgumentCaptor.forClass(AtomicBuffer.class);
        final ArgumentCaptor<Header> headerArg = ArgumentCaptor.forClass(Header.class);

        verify(delegateDataHandler, times(1)).onData(
            bufferArg.capture(), eq(offset), eq(length * 2), headerArg.capture());

        final AtomicBuffer capturedBuffer = bufferArg.getValue();
        for (int i = 0; i < srcBuffer.capacity(); i++)
        {
            assertThat("same at i=" + i, capturedBuffer.getByte(i), is(srcBuffer.getByte(i)));
        }

        final Header capturedHeader = headerArg.getValue();
        assertThat(capturedHeader.sessionId(), is(SESSION_ID));
        assertThat(capturedHeader.flags(), is(FrameDescriptor.UNFRAGMENTED));
    }

    @Test
    public void shouldAssembleFourPartMessage()
    {
        when(header.flags()).thenReturn(FrameDescriptor.BEGIN_FRAG)
                            .thenReturn((byte)0)
                            .thenReturn((byte)0)
                            .thenReturn(FrameDescriptor.END_FRAG);

        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);
        final int offset = 0;
        final int length = srcBuffer.capacity() / 4;

        for (int i = 0; i < 4; i++)
        {
            srcBuffer.setMemory(i * length, length, (byte)(65 + i));
        }

        adapter.onData(srcBuffer, offset, length, header);
        adapter.onData(srcBuffer, offset + length, length, header);
        adapter.onData(srcBuffer, offset + (length * 2), length, header);
        adapter.onData(srcBuffer, offset + (length * 3), length, header);

        final ArgumentCaptor<AtomicBuffer> bufferArg = ArgumentCaptor.forClass(AtomicBuffer.class);
        final ArgumentCaptor<Header> headerArg = ArgumentCaptor.forClass(Header.class);

        verify(delegateDataHandler, times(1)).onData(
            bufferArg.capture(), eq(offset), eq(length * 4), headerArg.capture());

        final AtomicBuffer capturedBuffer = bufferArg.getValue();
        for (int i = 0; i < srcBuffer.capacity(); i++)
        {
            assertThat("same at i=" + i, capturedBuffer.getByte(i), is(srcBuffer.getByte(i)));
        }

        final Header capturedHeader = headerArg.getValue();
        assertThat(capturedHeader.sessionId(), is(SESSION_ID));
        assertThat(capturedHeader.flags(), is(FrameDescriptor.UNFRAGMENTED));
    }

    @Test
    public void shouldFreeSessionBuffer()
    {
        when(header.flags()).thenReturn(FrameDescriptor.BEGIN_FRAG)
                            .thenReturn(FrameDescriptor.END_FRAG);

        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);
        final int offset = 0;
        final int length = srcBuffer.capacity() / 2;

        srcBuffer.setMemory(0, length, (byte)65);
        srcBuffer.setMemory(length, length, (byte)66);

        assertFalse(adapter.freeSessionBuffer(SESSION_ID));

        adapter.onData(srcBuffer, offset, length, header);
        adapter.onData(srcBuffer, length, length, header);

        assertTrue(adapter.freeSessionBuffer(SESSION_ID));
        assertFalse(adapter.freeSessionBuffer(SESSION_ID));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionIfEndFragComesBeforeBeginFrag()
    {
        when(header.flags()).thenReturn(FrameDescriptor.END_FRAG);
        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);
        final int offset = 0;
        final int length = srcBuffer.capacity() / 2;

        adapter.onData(srcBuffer, offset, length, header);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrowExceptionIfMidFragComesBeforeBeginFrag()
    {
        final AtomicBuffer srcBuffer = new AtomicBuffer(new byte[1024]);
        final int offset = 0;
        final int length = srcBuffer.capacity() / 2;

        adapter.onData(srcBuffer, offset, length, header);
    }
}