float sigmoid_of( float x ){
	return 1.0f / ( 1.0f + exp( -x ) );
}

__kernel __attribute__(( reqd_work_group_size( 256, 1, 1 ) ))
void lstm_forward_fused( __global const float* xw, __global const float* h0, __global const float* c0,
		__global const float* W, __global const float* b, __global float* gates, __global float* hout, __global float* cout,
		const int steps, const int B, const int in, const int s ){
	__local float hl[ 256 ];
	__local float gl[ 1024 ];
	int chunk = get_group_id( 0 );
	int lid = get_local_id( 0 );
	int G = 4 * s;
	float c = 0.0f;
	if (lid < s){
		hl[ lid ] = h0[ chunk * s + lid ];
		c = c0[ chunk * s + lid ];
	}
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int t = 0; t < steps; t++){
		int row = t * B + chunk;
		for (int j = lid; j < G; j += 256){
			float sum = b[ j ] + xw[ (long) row * G + j ];
			__global const float* w = W + (long) in * G + j;
			float a[ 8 ] = { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
			int r = 0;
			for (; r + 7 < s; r += 8){
				for (int u = 0; u < 8; u++) a[ u ] += hl[ r + u ] * w[ ( r + u ) * G ];
			}
			for (; r < s; r++) a[ 0 ] += hl[ r ] * w[ r * G ];
			sum += ( ( a[ 0 ] + a[ 1 ] ) + ( a[ 2 ] + a[ 3 ] ) ) + ( ( a[ 4 ] + a[ 5 ] ) + ( a[ 6 ] + a[ 7 ] ) );
			gates[ (long) row * G + j ] = sum;
			gl[ j ] = sum;
		}
		barrier( CLK_LOCAL_MEM_FENCE );
		if (lid < s){
			float f = sigmoid_of( gl[ lid ] );
			float ig = sigmoid_of( gl[ s + lid ] );
			float gt = tanh( gl[ 2 * s + lid ] );
			float o = sigmoid_of( gl[ 3 * s + lid ] );
			c = f * c + ig * gt;
			float h = o * tanh( c );
			cout[ (long) row * s + lid ] = c;
			hout[ (long) row * s + lid ] = h;
			hl[ lid ] = h;
		}
		barrier( CLK_LOCAL_MEM_FENCE );
	}
}

__kernel __attribute__(( reqd_work_group_size( 256, 1, 1 ) ))
void lstm_backward_fused( __global const float* gates, __global const float* c0, __global const float* cout,
		__global const float* dhHeads, __global const float* WT, __global float* gg,
		const int steps, const int B, const int in, const int s ){
	__local float dprev[ 256 ];
	__local float ggl[ 1024 ];
	__local float part[ 256 ];
	int chunk = get_group_id( 0 );
	int lid = get_local_id( 0 );
	int G = 4 * s, R = in + s;
	int parts = 256 / s;
	int pi = lid % s, pp = lid / s;
	int span = ( G + parts - 1 ) / parts;
	float dcell = 0.0f;
	if (lid < s) dprev[ lid ] = 0.0f;
	barrier( CLK_LOCAL_MEM_FENCE );
	for (int t = steps - 1; t >= 0; t--){
		int row = t * B + chunk;
		if (lid < s){
			__global const float* g = gates + (long) row * G;
			float f = sigmoid_of( g[ lid ] );
			float ig = sigmoid_of( g[ s + lid ] );
			float gt = tanh( g[ 2 * s + lid ] );
			float o = sigmoid_of( g[ 3 * s + lid ] );
			float cPrev = t == 0 ? c0[ chunk * s + lid ] : cout[ (long) ( row - B ) * s + lid ];
			float tc = tanh( cout[ (long) row * s + lid ] );
			float dOut = dhHeads[ (long) row * s + lid ] + dprev[ lid ];
			float dc = dcell + dOut * o * ( 1.0f - tc * tc );
			float v0 = dc * cPrev * f * ( 1.0f - f );
			float v1 = dc * gt * ig * ( 1.0f - ig );
			float v2 = dc * ig * ( 1.0f - gt * gt );
			float v3 = dOut * tc * o * ( 1.0f - o );
			__global float* d = gg + (long) row * G;
			d[ lid ] = v0;
			d[ s + lid ] = v1;
			d[ 2 * s + lid ] = v2;
			d[ 3 * s + lid ] = v3;
			ggl[ lid ] = v0;
			ggl[ s + lid ] = v1;
			ggl[ 2 * s + lid ] = v2;
			ggl[ 3 * s + lid ] = v3;
			dcell = dc * f;
		}
		barrier( CLK_LOCAL_MEM_FENCE );
		if (t == 0) break;
		float sum = 0.0f;
		if (pp < parts){
			int jTo = min( G, ( pp + 1 ) * span );
			__global const float* wt = WT + in + pi;
			float a[ 8 ] = { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
			int j = pp * span;
			for (; j + 7 < jTo; j += 8){
				for (int u = 0; u < 8; u++) a[ u ] += ggl[ j + u ] * wt[ (long) ( j + u ) * R ];
			}
			for (; j < jTo; j++) a[ 0 ] += ggl[ j ] * wt[ (long) j * R ];
			sum = ( ( a[ 0 ] + a[ 1 ] ) + ( a[ 2 ] + a[ 3 ] ) ) + ( ( a[ 4 ] + a[ 5 ] ) + ( a[ 6 ] + a[ 7 ] ) );
		}
		part[ lid ] = sum;
		barrier( CLK_LOCAL_MEM_FENCE );
		if (lid < s){
			float total = 0.0f;
			for (int q = 0; q < parts; q++) total += part[ q * s + lid ];
			dprev[ lid ] = total;
		}
		barrier( CLK_LOCAL_MEM_FENCE );
	}
}
