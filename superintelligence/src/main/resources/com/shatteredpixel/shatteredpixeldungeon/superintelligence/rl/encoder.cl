__kernel void build_concat( __global const float* reducePre, __global const float* extras, __global float* concat,
		const int features, const int extra ){
	int gid = get_global_id( 0 );
	int width = features + extra;
	int n = gid / width;
	int m = gid - n * width;
	concat[ gid ] = m < features ? fmax( reducePre[ n * features + m ], 0.0f ) : extras[ n * extra + m - features ];
}

__kernel void tanh_backward( __global const float* h, __global float* d ){
	int i = get_global_id( 0 );
	float t = h[ i ];
	d[ i ] *= 1.0f - t * t;
}

__kernel void relu_mask( __global const float* pre, __global float* d ){
	int i = get_global_id( 0 );
	if (pre[ i ] <= 0.0f) d[ i ] = 0.0f;
}

__kernel void transpose( __global const float* src, __global float* dst, const int rows, const int cols ){
	int gid = get_global_id( 0 );
	int c = gid / rows;
	int r = gid - c * rows;
	dst[ gid ] = src[ r * cols + c ];
}

__kernel void sum_rows( __global const float* src, __global float* dst, const int rowsPerGroup, const int width ){
	int gid = get_global_id( 0 );
	int g = gid / width;
	int j = gid - g * width;
	__global const float* s = src + (long) g * rowsPerGroup * width + j;
	float sum = 0.0f;
	for (int r = 0; r < rowsPerGroup; r++) sum += s[ (long) r * width ];
	dst[ gid ] = sum;
}

#define TS 64
#define TK 16

__kernel __attribute__(( reqd_work_group_size( 256, 1, 1 ) ))
void gemm( __global const float* A, __global const float* B, __global float* C,
		const int M, const int N, const int K, const int sAi, const int sAk, const int sBk, const int sBj,
		const int sCi, const int kSlice, const int tilesM, const int tilesN, const int sliceStride,
		const int aOff, const int bOff, const int cOff ){
	__local float As[ TK ][ TS ];
	__local float Bs[ TK ][ TS ];
	int lid = get_local_id( 0 );
	int tx = lid & 15, ty = lid >> 4;
	int g = get_group_id( 0 );
	int tn = g % tilesN;
	int tm = ( g / tilesN ) % tilesM;
	int slice = g / ( tilesN * tilesM );
	int i0 = tm * TS, j0 = tn * TS;
	int kFrom = slice * kSlice, kTo = min( K, kFrom + kSlice );
	A += aOff;
	B += bOff;
	float acc[ 4 ][ 4 ];
	for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) acc[ a ][ b ] = 0.0f;
	for (int k0 = kFrom; k0 < kTo; k0 += TK){
		for (int q = 0; q < 4; q++){
			int e = lid + 256 * q;
			int i, k;
			if (sAk == 1){ i = e / TK; k = e % TK; } else { i = e % TS; k = e / TS; }
			int gi = i0 + i, gk = k0 + k;
			As[ k ][ i ] = gi < M && gk < kTo ? A[ (long) gi * sAi + (long) gk * sAk ] : 0.0f;
			int j;
			if (sBj == 1){ k = e / TS; j = e % TS; } else { j = e / TK; k = e % TK; }
			int gj = j0 + j;
			gk = k0 + k;
			Bs[ k ][ j ] = gj < N && gk < kTo ? B[ (long) gk * sBk + (long) gj * sBj ] : 0.0f;
		}
		barrier( CLK_LOCAL_MEM_FENCE );
		for (int k = 0; k < TK; k++){
			float av[ 4 ], bv[ 4 ];
			for (int a = 0; a < 4; a++) av[ a ] = As[ k ][ ty + 16 * a ];
			for (int b = 0; b < 4; b++) bv[ b ] = Bs[ k ][ tx + 16 * b ];
			for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) acc[ a ][ b ] += av[ a ] * bv[ b ];
		}
		barrier( CLK_LOCAL_MEM_FENCE );
	}
	__global float* c = C + cOff + (long) slice * sliceStride;
	for (int a = 0; a < 4; a++){
		int gi = i0 + ty + 16 * a;
		if (gi >= M) continue;
		for (int b = 0; b < 4; b++){
			int gj = j0 + tx + 16 * b;
			if (gj < N) c[ (long) gi * sCi + gj ] = acc[ a ][ b ];
		}
	}
}

__kernel void slices_bias_tanh( __global const float* partial, __global const float* b, __global float* y,
		const int slices, const int out, const int stride ){
	int gid = get_global_id( 0 );
	float sum = b[ gid % out ];
	for (int s = 0; s < slices; s++) sum += partial[ (long) s * stride + gid ];
	y[ gid ] = tanh( sum );
}

float packed_input( __global const uchar* g, const int at, const int i, const int cell, const float levels ){
	return at >= 0 ? g[ at + cell ] / levels : (float) ( ( g[ i >> 3 ] >> ( i & 7 ) ) & 1 );
}

__kernel void conv_packed_rows( __global const uchar* packed, __global const int* planeLevels, __global const float* W,
		__global const float* b, __global float* pre, const int packedBytes, const int area, const float levels,
		const int inC, const int inW, const int outC, const int k, const int stride, const int outW, const int outH ){
	int gid = get_global_id( 0 );
	int positions = outW * outH;
	int n = gid / positions;
	int p = gid - n * positions;
	int oy = ( p / outW ) * stride;
	int ox = ( p % outW ) * stride;
	int quads = outC >> 2;
	__global const uchar* g = packed + (long) n * packedBytes;
	float4 acc[ 8 ];
	for (int q = 0; q < 8; q++) acc[ q ] = q < quads ? vload4( q, b ) : (float4) 0.0f;
	bool aligned = ( area & 7 ) == 0 && ( inW & 7 ) == 0 && k <= 8;
	for (int ch = 0; ch < inC; ch++){
		int at = planeLevels[ ch ];
		for (int ky = 0; ky < k; ky++){
			int row = ( oy + ky ) * inW + ox;
			if (aligned && at < 0){
				int bit = ch * area + row;
				uint bits = ( ( g[ bit >> 3 ] | ( g[ ( bit >> 3 ) + 1 ] << 8 ) ) >> ( bit & 7 ) ) & ( ( 1u << k ) - 1 );
				while (bits){
					int kx = 31 - (int) clz( bits & ( ~bits + 1 ) );
					bits &= bits - 1;
					__global const float* w = W + ( ( ch * k + ky ) * k + kx ) * outC;
					for (int q = 0; q < 8; q++) if (q < quads) acc[ q ] += vload4( q, w );
				}
				continue;
			}
			for (int kx = 0; kx < k; kx++){
				float v = packed_input( g, at, ch * area + row + kx, row + kx, levels );
				if (v == 0.0f) continue;
				__global const float* w = W + ( ( ch * k + ky ) * k + kx ) * outC;
				for (int q = 0; q < 8; q++) if (q < quads) acc[ q ] += v * vload4( q, w );
			}
		}
	}
	__global float* o = pre + (long) gid * outC;
	for (int q = 0; q < 8; q++) if (q < quads) vstore4( acc[ q ], q, o );
}

__kernel void conv_packed_weight( __global const uchar* packed, __global const int* planeLevels, __global const float* dPre,
		__global float* partial, const int packedBytes, const int area, const float levels,
		const int inC, const int inW, const int outC, const int k, const int stride, const int outW, const int outH,
		const int rowSlices, const int rows ){
	int gid = get_global_id( 0 );
	int patch = inC * k * k;
	int slice = gid / ( rows * patch );
	int nr = gid - slice * rows * patch;
	int n = nr / patch;
	int r = nr - n * patch;
	int ch = r / ( k * k );
	int ky = ( r / k ) % k;
	int kx = r % k;
	int at = planeLevels[ ch ];
	int per = ( outH + rowSlices - 1 ) / rowSlices;
	int yFrom = slice * per, yTo = min( outH, yFrom + per );
	int quads = outC >> 2;
	__global const uchar* g = packed + (long) n * packedBytes;
	__global const float* d = dPre + (long) n * outW * outH * outC;
	float4 acc[ 8 ];
	for (int q = 0; q < 8; q++) acc[ q ] = (float4) 0.0f;
	bool aligned = at < 0 && ( area & 7 ) == 0 && ( inW & 7 ) == 0 && inW <= 64;
	ulong lanes = 0;
	for (int ox = 0; ox < outW; ox++) lanes |= 1UL << ( ox * stride );
	for (int oy = yFrom; oy < yTo; oy++){
		int rowCell = ( oy * stride + ky ) * inW + kx;
		if (aligned){
			__global const uchar* rb = g + ( ( ch * area + rowCell - kx ) >> 3 );
			ulong row = 0;
			for (int b = 0; b < ( inW >> 3 ); b++) row |= (ulong) rb[ b ] << ( 8 * b );
			ulong m = ( row >> kx ) & lanes;
			while (m){
				int ox = ( 63 - (int) clz( m & ( ~m + 1 ) ) ) / stride;
				m &= m - 1;
				__global const float* dd = d + ( oy * outW + ox ) * outC;
				for (int q = 0; q < 8; q++) if (q < quads) acc[ q ] += vload4( q, dd );
			}
			continue;
		}
		for (int ox = 0; ox < outW; ox++){
			int cell = rowCell + ox * stride;
			float v = packed_input( g, at, ch * area + cell, cell, levels );
			if (v == 0.0f) continue;
			__global const float* dd = d + ( oy * outW + ox ) * outC;
			for (int q = 0; q < 8; q++) if (q < quads) acc[ q ] += v * vload4( q, dd );
		}
	}
	__global float* o = partial + (long) gid * outC;
	for (int q = 0; q < 8; q++) if (q < quads) vstore4( acc[ q ], q, o );
}

__kernel void im2col( __global const float* in, __global float* col, const int inC, const int inW, const int inH,
		const int k, const int stride, const int outW, const int outH, const int reluIn, const int total ){
	int gid = get_global_id( 0 );
	int r = gid / total;
	int row = gid - r * total;
	int positions = outW * outH;
	int n = row / positions;
	int p = row - n * positions;
	int ch = r / ( k * k );
	int ky = ( r / k ) % k;
	int kx = r % k;
	float v = in[ (long) n * inC * inW * inH + ch * inW * inH + ( ( p / outW ) * stride + ky ) * inW + ( p % outW ) * stride + kx ];
	col[ gid ] = reluIn ? fmax( v, 0.0f ) : v;
}

__kernel void col2im( __global const float* col, __global float* dIn, const int inC, const int inW, const int inH,
		const int k, const int stride, const int outW, const int outH, const int total ){
	int gid = get_global_id( 0 );
	int plane = inW * inH;
	int n = gid / ( inC * plane );
	int r = gid - n * inC * plane;
	int ch = r / plane;
	int iy = ( r % plane ) / inW;
	int ix = r % inW;
	__global const float* c = col + (long) n * outW * outH;
	float sum = 0.0f;
	for (int ky = 0; ky < k; ky++){
		int ty = iy - ky;
		if (ty < 0 || ty % stride != 0 || ty / stride >= outH) continue;
		for (int kx = 0; kx < k; kx++){
			int tx = ix - kx;
			if (tx < 0 || tx % stride != 0 || tx / stride >= outW) continue;
			sum += c[ (long) ( ( ch * k + ky ) * k + kx ) * total + ( ty / stride ) * outW + tx / stride ];
		}
	}
	dIn[ gid ] = sum;
}

__kernel void add_bias( __global float* y, __global const float* b, const int out ){
	int gid = get_global_id( 0 );
	y[ gid ] += b[ gid % out ];
}

__kernel void column_sum( __global const float* src, __global float* dst, const int rows, const int width ){
	__local float part[ 256 ];
	int j = get_group_id( 0 );
	int lid = get_local_id( 0 );
	float sum = 0.0f;
	for (int r = lid; r < rows; r += 256) sum += src[ (long) r * width + j ];
	part[ lid ] = sum;
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int k = 128; k > 0; k >>= 1){
		if (lid < k) part[ lid ] += part[ lid + k ];
		barrier( CLK_LOCAL_MEM_FENCE );
	}
	if (lid == 0) dst[ j ] = part[ 0 ];
}
