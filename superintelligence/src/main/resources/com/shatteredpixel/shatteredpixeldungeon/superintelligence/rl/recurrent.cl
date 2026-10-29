float sigmoid_of( float x ){
	return 1.0f / ( 1.0f + exp( -x ) );
}

__kernel void lstm_gates( __global const float* X, __global const float* h0, __global const float* hout,
		__global const float* W, __global const float* b, __global float* gates,
		const int t, const int B, const int in, const int s ){
	int gid = get_global_id( 0 );
	int G = 4 * s;
	int bb = gid / G;
	int j = gid - bb * G;
	int row = t * B + bb;
	__global const float* x = X + (long) row * in;
	__global const float* h = t == 0 ? h0 + bb * s : hout + (long) ( row - B ) * s;
	float sum = b[ j ];
	for (int r = 0; r < in; r++) sum += x[ r ] * W[ r * G + j ];
	for (int r = 0; r < s; r++) sum += h[ r ] * W[ ( in + r ) * G + j ];
	gates[ (long) row * G + j ] = sum;
}

__kernel void lstm_cell( __global const float* gates, __global const float* c0, __global float* cout,
		__global float* hout, const int t, const int B, const int s ){
	int gid = get_global_id( 0 );
	int bb = gid / s;
	int i = gid - bb * s;
	int row = t * B + bb;
	__global const float* g = gates + (long) row * 4 * s;
	float f = sigmoid_of( g[ i ] );
	float in = sigmoid_of( g[ s + i ] );
	float gt = tanh( g[ 2 * s + i ] );
	float o = sigmoid_of( g[ 3 * s + i ] );
	float cPrev = t == 0 ? c0[ bb * s + i ] : cout[ (long) ( row - B ) * s + i ];
	float c = f * cPrev + in * gt;
	cout[ (long) row * s + i ] = c;
	hout[ (long) row * s + i ] = o * tanh( c );
}

__kernel void dense_forward_linear( __global const float* x, __global const float* W, __global const float* b,
		__global float* y, const int in, const int out ){
	int gid = get_global_id( 0 );
	int n = gid / out;
	int j = gid - n * out;
	__global const float* xr = x + (long) n * in;
	float sum = b[ j ];
	for (int m = 0; m < in; m++) sum += xr[ m ] * W[ m * out + j ];
	y[ gid ] = sum;
}

__kernel void lstm_cell_backward( __global const float* gates, __global const float* c0, __global const float* cout,
		__global const float* dhHeads, __global const float* dPrev, __global float* dCell, __global float* gg,
		const int t, const int B, const int s ){
	int gid = get_global_id( 0 );
	int bb = gid / s;
	int i = gid - bb * s;
	int row = t * B + bb;
	__global const float* g = gates + (long) row * 4 * s;
	float f = sigmoid_of( g[ i ] );
	float in = sigmoid_of( g[ s + i ] );
	float gt = tanh( g[ 2 * s + i ] );
	float o = sigmoid_of( g[ 3 * s + i ] );
	float cPrev = t == 0 ? c0[ bb * s + i ] : cout[ (long) ( row - B ) * s + i ];
	float tc = tanh( cout[ (long) row * s + i ] );
	float dOut = dhHeads[ (long) row * s + i ] + dPrev[ bb * s + i ];
	float dc = dCell[ bb * s + i ] + dOut * o * ( 1.0f - tc * tc );
	__global float* d = gg + (long) row * 4 * s;
	d[ i ] = dc * cPrev * f * ( 1.0f - f );
	d[ s + i ] = dc * gt * in * ( 1.0f - in );
	d[ 2 * s + i ] = dc * in * ( 1.0f - gt * gt );
	d[ 3 * s + i ] = dOut * tc * o * ( 1.0f - o );
	dCell[ bb * s + i ] = dc * f;
}

__kernel void lstm_input_grad( __global const float* gg, __global const float* WT, __global float* dX,
		__global float* dPrev, const int t, const int B, const int in, const int s ){
	int gid = get_global_id( 0 );
	int R = in + s;
	int G = 4 * s;
	int bb = gid / R;
	int r = gid - bb * R;
	int row = t * B + bb;
	__global const float* d = gg + (long) row * G;
	float sum = 0.0f;
	for (int j = 0; j < G; j++) sum += d[ j ] * WT[ j * R + r ];
	if (r < in) dX[ (long) row * in + r ] = sum;
	else dPrev[ bb * s + r - in ] = sum;
}

__kernel void lstm_weight_grad( __global const float* X, __global const float* h0, __global const float* hout,
		__global const float* gg, __global float* gW, const int rows, const int B, const int in, const int s ){
	int gid = get_global_id( 0 );
	int G = 4 * s;
	int r = gid / G;
	int j = gid - r * G;
	float sum = 0.0f;
	for (int row = 0; row < rows; row++){
		float v = r < in ? X[ (long) row * in + r ]
				: row < B ? h0[ row * s + r - in ] : hout[ (long) ( row - B ) * s + r - in ];
		sum += v * gg[ (long) row * G + j ];
	}
	gW[ gid ] = sum;
}
