/******************************************************************************

Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz)
                    and Stephen Freund (Williams College) 

All rights reserved.  

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

 * Neither the names of the University of California, Santa Cruz
      and Williams College nor the names of its contributors may be
      used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 ******************************************************************************/

package tools.wdc;

import acme.util.identityhash.WeakIdentityHashMap;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import tools.wdc.event.EventNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

public class WDCLockData {

	public final ShadowLock peer;
	public final CV hb;
	public final CV wcp;
	public final CV wdc;
	public final CV wbr;
	public final CV lshe;
	public HashSet<ShadowVar> readVars; // variables read during critical section
	public HashSet<ShadowVar> writeVars; // variables written during critical section
	public WeakIdentityHashMap<ShadowVar,CV> wcpReadMap;
	public WeakIdentityHashMap<ShadowVar,CV> wcpWriteMap;
	public WeakIdentityHashMap<ShadowVar,CV> wdcReadMap;
	public WeakIdentityHashMap<ShadowVar,CV> wdcWriteMap;
	public WeakIdentityHashMap<ShadowVar,CVE> wbrWriteMap;
	public final HashMap<ShadowThread,ArrayDeque<CV>> wcpAcqQueueMap;
	public final HashMap<ShadowThread,ArrayDeque<CV>> wcpRelQueueMap;
	public final ArrayDeque<CV> wcpAcqQueueGlobal;
	public final ArrayDeque<CV> wcpRelQueueGlobal;
	
	public final HashMap<ShadowThread,PerThreadQueue<CV>> wdcAcqQueueMap;
	public final HashMap<ShadowThread,PerThreadQueue<CV>> wdcRelQueueMap;
	public final PerThreadQueue<CV> wdcAcqQueueGlobal;
	public final PerThreadQueue<CV> wdcRelQueueGlobal;

	public final HashMap<ShadowThread,PerThreadQueue<CVE>> wbrAcqQueueMap;
	public final HashMap<ShadowThread,PerThreadQueue<CVE>> wbrRelQueueMap;
	public final PerThreadQueue<CVE> wbrAcqQueueGlobal;
	public final PerThreadQueue<CVE> wbrRelQueueGlobal;

	public EventNode latestRelNode;

	public WDCLockData(ShadowLock ld) {
		this.peer = ld;
		this.hb = new CV(WDCTool.INIT_CV_SIZE);
		this.wcp = new CV(WDCTool.INIT_CV_SIZE);
		this.wdc = new CV(WDCTool.INIT_CV_SIZE);
		this.readVars = new HashSet<>();
		this.writeVars = new HashSet<>();
		this.wcpReadMap = new WeakIdentityHashMap<>();
		this.wcpWriteMap = new WeakIdentityHashMap<>();
		this.wdcReadMap = new WeakIdentityHashMap<>();
		this.wdcWriteMap = new WeakIdentityHashMap<>();
		this.wbrWriteMap = new WeakIdentityHashMap<>();
		this.wcpAcqQueueMap = new HashMap<>();
		this.wcpRelQueueMap = new HashMap<>();
		this.wcpAcqQueueGlobal = new ArrayDeque<>();
		this.wcpRelQueueGlobal = new ArrayDeque<>();

		if (WDCTool.DC || WDCTool.HB_WCP_DC || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			this.wdcAcqQueueMap = new HashMap<>();
			this.wdcRelQueueMap = new HashMap<>();
			this.wdcAcqQueueGlobal = new PerThreadQueue<>();
			this.wdcRelQueueGlobal = new PerThreadQueue<>();
		} else {
			this.wdcAcqQueueMap = null;
			this.wdcRelQueueMap = null;
			this.wdcAcqQueueGlobal = null;
			this.wdcRelQueueGlobal = null;
		}

		if (WDCTool.WBR || WDCTool.WCP_WBR || WDCTool.WCP_DC_WBR || WDCTool.WCP_DC_WBR_LSHE) {
			this.wbrAcqQueueMap = new HashMap<>();
			this.wbrRelQueueMap = new HashMap<>();
			this.wbrAcqQueueGlobal = new PerThreadQueue<>();
			this.wbrRelQueueGlobal = new PerThreadQueue<>();
		} else {
			this.wbrAcqQueueMap = null;
			this.wbrRelQueueMap = null;
			this.wbrAcqQueueGlobal = null;
			this.wbrRelQueueGlobal = null;
		}
		
		latestRelNode = null;
		wbr = new CV(WDCTool.INIT_CV_SIZE);
		
		lshe = new CV(WDCTool.INIT_CV_SIZE);
	}

	@Override
	public String toString() {
		return String.format("[HB=%s] [WCP=%s] [WDC=%s]", hb, wcp, wdc);
	}
	
}
