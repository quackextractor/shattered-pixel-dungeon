__kernel void unpack_grid( __global const uchar* packed, __global const int* planeLevels, __global float* grid,
		const int cells, const int area, const int packedBytes, const float levels ){
	int gid = get_global_id( 0 );
	int n = gid / cells;
	int i = gid - n * cells;
	__global const uchar* p = packed + (long) n * packedBytes;
	int at = planeLevels[ i / area ];
	grid[ gid ] = at >= 0 ? p[ at + i % area ] / levels : (float) ( ( p[ i >> 3 ] >> ( i & 7 ) ) & 1 );
}

__kernel void conv_forward( __global const float* in, __global const float* W, __global const float* b,
		__global float* pre, const int inC, const int inW, const int inH, const int outC, const int k,
		const int stride, const int outW, const int outH, const int reluIn ){
	int gid = get_global_id( 0 );
	int perSample = outW * outH * outC;
	int n = gid / perSample;
	int r = gid - n * perSample;
	int p = r / outC;
	int q = r - p * outC;
	int oy = ( p / outW ) * stride;
	int ox = ( p % outW ) * stride;
	int plane = inW * inH;
	__global const float* x = in + (long) n * inC * plane;
	float sum = b[ q ];
	for (int ch = 0; ch < inC; ch++){
		for (int ky = 0; ky < k; ky++){
			__global const float* row = x + ch * plane + ( oy + ky ) * inW + ox;
			__global const float* w = W + ( ( ch * k + ky ) * k ) * outC + q;
			for (int kx = 0; kx < k; kx++){
				float v = row[ kx ];
				if (reluIn) v = fmax( v, 0.0f );
				sum += v * w[ kx * outC ];
			}
		}
	}
	pre[ gid ] = sum;
}

__kernel void build_concat( __global const float* reducePre, __global const float* extras, __global float* concat,
		const int features, const int extra ){
	int gid = get_global_id( 0 );
	int width = features + extra;
	int n = gid / width;
	int m = gid - n * width;
	concat[ gid ] = m < features ? fmax( reducePre[ n * features + m ], 0.0f ) : extras[ n * extra + m - features ];
}

__kernel void dense_forward_tanh( __global const float* x, __global const float* W, __global const float* b,
		__global float* y, const int in, const int out ){
	int gid = get_global_id( 0 );
	int n = gid / out;
	int j = gid - n * out;
	__global const float* xr = x + (long) n * in;
	float sum = b[ j ];
	for (int m = 0; m < in; m++) sum += xr[ m ] * W[ m * out + j ];
	y[ gid ] = tanh( sum );
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

__kernel void dense_weight_grad( __global const float* x, __global const float* dy, __global float* gW,
		const int rows, const int in, const int out ){
	int gid = get_global_id( 0 );
	int m = gid / out;
	int j = gid - m * out;
	float sum = 0.0f;
	for (int n = 0; n < rows; n++) sum += x[ (long) n * in + m ] * dy[ n * out + j ];
	gW[ gid ] = sum;
}

__kernel void transpose( __global const float* src, __global float* dst, const int rows, const int cols ){
	int gid = get_global_id( 0 );
	int c = gid / rows;
	int r = gid - c * rows;
	dst[ gid ] = src[ r * cols + c ];
}

__kernel void dense_input_grad( __global const float* dy, __global const float* WT, __global float* dx,
		const int used, const int in, const int out ){
	int gid = get_global_id( 0 );
	int n = gid / used;
	int m = gid - n * used;
	__global const float* d = dy + (long) n * out;
	float sum = 0.0f;
	for (int j = 0; j < out; j++) sum += d[ j ] * WT[ j * in + m ];
	dx[ gid ] = sum;
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

__kernel void conv_weight_partial( __global const float* in, __global const float* dPre, __global float* partial,
		const int inC, const int inW, const int inH, const int outC, const int k, const int stride,
		const int outW, const int outH, const int reluIn ){
	int gid = get_global_id( 0 );
	int perSample = inC * k * k * outC;
	int n = gid / perSample;
	int rq = gid - n * perSample;
	int r = rq / outC;
	int q = rq - r * outC;
	int ch = r / ( k * k );
	int ky = ( r / k ) % k;
	int kx = r % k;
	int plane = inW * inH;
	int positions = outW * outH;
	__global const float* x = in + (long) n * inC * plane + ch * plane + ky * inW + kx;
	__global const float* d = dPre + (long) n * positions * outC + q;
	float sum = 0.0f;
	for (int p = 0; p < positions; p++){
		float v = x[ ( p / outW ) * stride * inW + ( p % outW ) * stride ];
		if (reluIn) v = fmax( v, 0.0f );
		sum += v * d[ p * outC ];
	}
	partial[ gid ] = sum;
}

__kernel void conv_input_grad( __global const float* dPre, __global const float* W, __global float* dIn,
		const int inC, const int inW, const int inH, const int outC, const int k, const int stride,
		const int outW, const int outH ){
	int gid = get_global_id( 0 );
	int plane = inW * inH;
	int perSample = inC * plane;
	int n = gid / perSample;
	int r = gid - n * perSample;
	int ch = r / plane;
	int iy = ( r % plane ) / inW;
	int ix = r % inW;
	__global const float* d = dPre + (long) n * outW * outH * outC;
	float sum = 0.0f;
	for (int ky = 0; ky < k; ky++){
		int ty = iy - ky;
		if (ty < 0 || ty % stride != 0 || ty / stride >= outH) continue;
		for (int kx = 0; kx < k; kx++){
			int tx = ix - kx;
			if (tx < 0 || tx % stride != 0 || tx / stride >= outW) continue;
			__global const float* dd = d + ( ( ty / stride ) * outW + tx / stride ) * outC;
			__global const float* w = W + ( ( ch * k + ky ) * k + kx ) * outC;
			for (int q = 0; q < outC; q++) sum += dd[ q ] * w[ q ];
		}
	}
	dIn[ gid ] = sum;
}
