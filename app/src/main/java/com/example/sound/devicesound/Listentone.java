package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.support.annotation.NonNull;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;


import java.util.ArrayList;

import static java.lang.Math.abs;
import static java.lang.Math.min;

import java.util.List;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false; //in_packet
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }


    public void PreRequest(){  //listen_linux()
        int blocksize = findPowerSize((int)(long)Math.round(interval/2*mSampleRate)); //num_frames
        short[] buffer = new short[blocksize];
        double[] chunk = new double[blocksize]; //double[] toTransform
        List<Double> packet = new ArrayList<>(); //packet=[]
        List<Integer> byte_stream = new ArrayList<>();

        while(true) {

            int bufferedReadResult = mAudioRecord.read(buffer, 0,blocksize); //mic.read() buffer에 저장

            for(int i = 0; i<blocksize; i++){
                chunk[i] = buffer[i]; //chunk =  np.fromstring(data, dtype=np.int16)
            }
            //Log.d("chunk", chunk.toString());

            double dom = findFrequency(chunk); //findFrequency return double
            Log.d("Listentone", "freq : " + dom);

            //startFlag == in_packet == false
            if (startFlag && match(dom, HANDSHAKE_END_HZ)){ //수신종료 주파수를 만나면 실행
                byte_stream = extract_packet(packet); //ascii
                Log.d("Listentone", "original code : " + byte_stream.toString());

                String data = ""; //데이터를 넣을 문자열

                for(int i = 0; i< byte_stream.size()-4; i++){ //ascii > 문자로 (오류코드 뺌)
                    data = data + (char)(byte_stream.get(i).intValue()); //byte_stream = byte_stream.decode("utf-8")
                }

               // String data = new String(byte_stream, "utf-8");
                Log.d("Listentone", "result : " + data.toString());

                packet.clear(); //packet = []
                startFlag = false;
            }
            else if (startFlag) {
                packet.add(dom); //계속 주파수를 받기만 한다.
            }
            else if (match(dom, HANDSHAKE_START_HZ)) {
                startFlag = true;
            }
        }


    }

    public int findPowerSize( int size ) {   //2의 제곱수
        int i = 1;
        while(true){
            int poweroftwo = (int)Math.pow(2, i);
            if(poweroftwo >= size)
                return poweroftwo;
            i++;
        }
    }


    public List<Integer> decode_bitchunks(int chunk_bits, List<Integer> chunks) { //주파수 데이터를 4비트로 분할 decode.py def decode_bitchunks
         List<Integer> out_bytes = new ArrayList<>();


        int next_read_chunk = 0;
        int next_read_bit = 0;

        int bytes = 0;
        int bits_left = 8;

        while (next_read_chunk < chunks.size()) {
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = min(bits_left, can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            bytes <<= to_fill;
            int shifted = chunks.get(next_read_chunk) & (((1<< to_fill)-1) << offset);
            bytes |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;

            if(bits_left <= 0) {
                out_bytes.add(bytes);
                bytes = 0;
                bits_left = 8;
            }

            if(next_read_bit >= chunk_bits) {
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }

        }
        return out_bytes;
    }

    public List<Integer> extract_packet(List<Double> freqs) { //freqs = packet decode.py extract_packet
        //List<Double> freq = new ArrayList<>(); // 생플링 두번했으니까 한번만 한거 저장
        List<Integer> bit_chunks = new ArrayList<>();
        List<Integer> re_bit_chunks = new ArrayList<>();

        /* 두번씩 안나와서 주석처리
        for(int i = 0; i< freqs.size(); i++){ // 0, 2, 4, 6, 8 ...
            freq.add(freqs.get(i));
        }
        Log.d("freqs", freqs.toString());
        */

        for(int i = 0; i < freqs.size(); i++){
            bit_chunks.add((int)(Math.round((freqs.get(i) - START_HZ)/STEP_HZ)));
        }

        for(int i = 1; i<bit_chunks.size(); i++){ //bit_chunks = [c for c in bit_chunks[1:] if 0 <= c < (2 ** BITS)]
            int c = bit_chunks.get(i);
            if((c>=0) && (c<(int)(Math.pow(2, BITS))))
                re_bit_chunks.add(c);
        }
        //ByteBuffer.allocate(4).putInt(value).array();
        //
        //출처: https://dev.re.kr/24 [Dev.re.kr]
        // listen_linux에서 byte_stream.decode("utf-8")을 똑같이 해보려고 시도
//        int size = decode_bitchunks(BITS, re_bit_chunks).size();
//        byte [] bytearray = new byte[size];
//        for(int i = 0; i<size; i++){
//           bytearray = ByteBuffer.allocate(4).putInt(decode_bitchunks(BITS, re_bit_chunks).get(i)).array();
//        }

        return decode_bitchunks(BITS, re_bit_chunks);
    }

    public double findFrequency(double[] toTransform) { //dominant  //chunk =  np.fromstring(data, dtype=np.int16)
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);

        for(int i = 0; i<complx.length; i++) {
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
        }
        //Log.d("mag", mag.toString());
        //dominant
        double peak_coeff = 0;
        int temp = 0;
        for(int i = 0; i< complx.length; i++){
            if(peak_coeff< mag[i]){  //argmax(abs(mag[i])) 제일 큰 값을 넣음
                peak_coeff = mag[i];
                temp = i;
            }
        }
        Double peak_freq = freq[temp]; //peak_freq
        return Math.abs(peak_freq*mSampleRate); //가장 특징적인 주파수 *
    }

    public Double[] fftfreq(int complxsize, int num) {
        double val = 1.0 / (complxsize * num);
        int[] results = new int[complxsize];
        Double[] normal_results = new Double[complxsize];
        int N = (complxsize - 1)/2 + 1;

        for(int i = 0; i<N; i++){
            results[i] = i;
        }
        int temp = -(complxsize/2);
        for(int i = N; i<0; i++){
            results[i] = temp;
            temp++;
        }

        for(int i=0; i<results.length; i++){
            normal_results[i] = results[i] * val;
        }

        return normal_results;
    }


    public boolean match(double freq1, double freq2) {
        return Math.abs(freq1 - freq2) < 20;
    }

}
